import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A/B harness for the Trino spooled client protocol.
 *
 * Each iteration times a full drain of the result set from a fresh
 * connection, so the numbers include connection setup, the coordinator
 * handshake, and -- under Profile B -- the client's own S3 segment
 * fetches. Every column of every row is materialized via getObject so
 * that deserialization cost is actually paid rather than optimized away.
 *
 * Usage:
 *   java -cp trino-jdbc.jar Bench.java \
 *        --url jdbc:trino://host:8080/catalog \
 *        --encoding none|json|json+lz4|json+zstd \
 *        --iters 5 --warmup 1 --label baseline \
 *        --sql "SELECT ..." --out results/baseline.tsv
 */
public final class Bench
{
    private Bench() {}

    public static void main(String[] args)
            throws Exception
    {
        String url = null;
        String encoding = "none";
        String label = "run";
        String sql = null;
        String out = null;
        String user = "bench";
        int iters = 5;
        int warmup = 1;

        for (int i = 0; i < args.length - 1; i += 2) {
            String v = args[i + 1];
            switch (args[i]) {
                case "--url" -> url = v;
                case "--encoding" -> encoding = v;
                case "--label" -> label = v;
                case "--sql" -> sql = v;
                case "--out" -> out = v;
                case "--user" -> user = v;
                case "--iters" -> iters = Integer.parseInt(v);
                case "--warmup" -> warmup = Integer.parseInt(v);
                default -> throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        if (url == null || sql == null) {
            throw new IllegalArgumentException("--url and --sql are required");
        }

        Properties props = new Properties();
        props.setProperty("user", user);
        // "none" leaves the property unset. That does NOT opt out of
        // spooling: the 478 driver negotiates a spooled encoding on its own
        // whenever the server offers one, so an unset property against a
        // spooling-enabled server still spools (measured: 15.8s and a 3.7 KB
        // coordinator outputDataSize, versus 48.6s and 234 MiB inline).
        // A genuine baseline therefore requires protocol.spooling.enabled=
        // false on the server, which is what Profile A does.
        if (!"none".equals(encoding)) {
            props.setProperty("encoding", encoding);
        }

        // Derive the coordinator's HTTP base from the JDBC URL so query
        // stats can be pulled back for each run.
        String authority = url.substring("jdbc:trino://".length()).split("/")[0];
        String statsBase = "http://" + authority + "/v1/query/";
        HttpClient http = HttpClient.newHttpClient();

        System.out.printf(Locale.ROOT,
                "label=%s encoding=%s iters=%d warmup=%d%nurl=%s%n",
                label, encoding, iters, warmup, url);

        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < warmup + iters; i++) {
            boolean isWarmup = i < warmup;
            Run r = runOnce(url, props, sql);
            r.queryStats = fetchStats(http, statsBase, r.queryId);
            if (!isWarmup) {
                runs.add(r);
            }
            System.out.printf(Locale.ROOT,
                    "  %-7s #%d  ttfr=%7.1f ms  total=%8.1f ms  rows=%d  cells=%d  queryId=%s%n",
                    isWarmup ? "warmup" : "measure",
                    isWarmup ? i + 1 : i - warmup + 1,
                    r.ttfrMillis, r.totalMillis, r.rows, r.cells, r.queryId);
        }

        report(label, encoding, runs);
        if (out != null) {
            writeTsv(Path.of(out), label, encoding, sql, runs);
            System.out.println("wrote " + out);
        }
    }

    private static Run runOnce(String url, Properties props, String sql)
            throws Exception
    {
        Run r = new Run();
        long t0 = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url, props);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            r.columns = cols;

            boolean first = true;
            long rows = 0;
            long cells = 0;
            while (rs.next()) {
                if (first) {
                    r.ttfrMillis = (System.nanoTime() - t0) / 1e6;
                    first = false;
                }
                for (int c = 1; c <= cols; c++) {
                    Object o = rs.getObject(c);
                    if (o != null) {
                        cells++;
                    }
                }
                rows++;
            }
            r.rows = rows;
            r.cells = cells;
            if (first) {
                // Empty result: no first row was ever observed.
                r.ttfrMillis = (System.nanoTime() - t0) / 1e6;
            }
            r.queryId = queryIdOf(rs);
        }
        r.totalMillis = (System.nanoTime() - t0) / 1e6;
        return r;
    }

    /**
     * TrinoResultSet exposes the query id, but the class is only present
     * on the driver's classpath, so it is reached reflectively to keep
     * this file compilable against the plain JDBC API.
     */
    private static String queryIdOf(ResultSet rs)
    {
        try {
            return (String) rs.getClass().getMethod("getQueryId").invoke(rs);
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return "unknown";
        }
    }

    private static String fetchStats(HttpClient http, String base, String queryId)
    {
        if (queryId == null || "unknown".equals(queryId)) {
            return "";
        }
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(base + queryId))
                            .header("X-Trino-User", "bench")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : "";
        }
        catch (IOException | InterruptedException e) {
            return "";
        }
    }

    private static void report(String label, String encoding, List<Run> runs)
    {
        if (runs.isEmpty()) {
            System.out.println("no measured iterations");
            return;
        }
        double[] totals = runs.stream().mapToDouble(x -> x.totalMillis).toArray();
        double[] ttfrs = runs.stream().mapToDouble(x -> x.ttfrMillis).toArray();
        long rows = runs.get(0).rows;
        double medTotal = median(totals);

        System.out.printf(Locale.ROOT,
                "%n== %s (encoding=%s) over %d runs%n"
                        + "   total  median=%.1f ms  min=%.1f  max=%.1f%n"
                        + "   ttfr   median=%.1f ms  min=%.1f  max=%.1f%n"
                        + "   rows=%d  throughput(median)=%.0f rows/s%n",
                label, encoding, runs.size(),
                medTotal, min(totals), max(totals),
                median(ttfrs), min(ttfrs), max(ttfrs),
                rows, rows / (medTotal / 1000.0));
    }

    private static void writeTsv(Path path, String label, String encoding, String sql, List<Run> runs)
            throws IOException
    {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("label\tencoding\titeration\tttfr_ms\ttotal_ms\trows\tcells\tcolumns\tquery_id\n");
            int i = 1;
            for (Run r : runs) {
                w.write(String.join("\t",
                        label, encoding, Integer.toString(i++),
                        String.format(Locale.ROOT, "%.1f", r.ttfrMillis),
                        String.format(Locale.ROOT, "%.1f", r.totalMillis),
                        Long.toString(r.rows), Long.toString(r.cells),
                        Integer.toString(r.columns), r.queryId));
                w.write("\n");
            }
        }
        // Server-side stats are kept beside the TSV so the client numbers
        // can be cross-checked against what the coordinator recorded.
        Path statsPath = path.resolveSibling(path.getFileName() + ".stats.json");
        try (BufferedWriter w = Files.newBufferedWriter(statsPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("[\n");
            for (int i = 0; i < runs.size(); i++) {
                String body = runs.get(i).queryStats;
                w.write(body == null || body.isEmpty() ? "null" : body);
                w.write(i == runs.size() - 1 ? "\n" : ",\n");
            }
            w.write("]\n");
        }
        System.out.println("wrote " + statsPath);
        System.out.println("sql: " + sql);
    }

    private static double median(double[] v)
    {
        double[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    private static double min(double[] v)
    {
        return Arrays.stream(v).min().orElse(Double.NaN);
    }

    private static double max(double[] v)
    {
        return Arrays.stream(v).max().orElse(Double.NaN);
    }

    private static final class Run
    {
        double ttfrMillis;
        double totalMillis;
        long rows;
        long cells;
        int columns;
        String queryId;
        String queryStats = "";
    }
}
