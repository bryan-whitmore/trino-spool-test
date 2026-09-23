# Runbook — A/B: baseline JDBC vs Trino spooled client protocol on VAST

Measures the Trino 478 **spooled client protocol** against the classic client
protocol, using a VAST S3 bucket as the spool store and VAST Database as the
data source.

- **Query under test:** `SELECT * FROM var203."synthetic-datasets/tables".events LIMIT 100000`
  — 100,000 rows × 265 columns = 26.5M cells, 234 MiB of result data. The table
  is synthetic (`time_dim_01`, `dim_02…dim_09`, `metric_000…metric_255`).
- **Trino:** `trinodb/trino:478`, container `trino-spool`, port **8080**
- **Connector:** `trino-vast-5.5.0-478-e92442418427`
- **Client:** `trino-jdbc-478.jar`, Java 21

All cluster-specific values — credentials, endpoints, CNode IPs, bucket —
live in `.env`, which is gitignored. Every tracked file uses placeholders, so
nothing here discloses an environment. Copy `.env.example` to `.env` and fill
it in.

---

## Step 0 — VAST-side prerequisites (operator action)

Everything in this step is **cluster configuration you must supply**; the rest
of the runbook is automated.

### 0.1 S3 bucket / view for the spool store

The spooling manager needs an S3-protocol path it can fully manage. This
runbook spools to a prefix inside the same bucket that holds the database:

| Setting | Value |
|---|---|
| Bucket | `synthetic-datasets` |
| Spool prefix | `trino-spooling/` |
| S3 endpoint | `$S3_ENDPOINT` (cluster metadata VIP) |
| Region | `us-east-1` (nominal; VAST ignores it, the S3 client requires it) |
| Addressing | **path-style** — VAST has no per-bucket DNS |
| View protocols | **S3** on the spool prefix; the table prefix needs **DATABASE** |

The two prefixes are served by different protocols and that is expected: the
database prefix may well deny plain S3 `ListObjectsV2` while the connector
reads it happily over the DATABASE protocol. Only the spool prefix needs S3.

The identity Trino uses must be able to do all of the following on
`$SPOOL_BUCKET/$SPOOL_PREFIX/*`. Spooling is **not read-mostly** — it creates,
reads, and then deletes an object for every result segment:

- `PutObject`, including multipart (`CreateMultipartUpload`, `UploadPart`,
  `CompleteMultipartUpload`, `AbortMultipartUpload`)
- `GetObject`
- `ListBucket`, prefix-scoped — required by the segment pruner
- `DeleteObject` **and** `DeleteObjects` (bulk — used by pruning batches)
- Pre-signed `GetObject` honoured for **unauthenticated** callers, because
  `retrieval-mode=STORAGE` has the *JDBC client*, not the coordinator, fetch
  each segment from a signed URL

### 0.2 Identity and keys

An S3 access key / secret pair for a user or role holding the rights above.
This runbook reuses the VAST Database tabular identity, so one credential
covers both the catalog and the spool store. Put it in `.env`.

### 0.3 Verify before proceeding

```bash
./verify-bucket.sh
```

Probes PUT, prefix LIST, GET, multipart PUT, pre-signed GET, DELETE, and bulk
DELETE, and fails loudly on the first gap. All seven must pass. Run this
first: a missing bulk-delete or pre-sign right does not fail at startup — it
fails mid-query, or silently leaks segment objects that nothing ever reaps.

---

## Step 1 — Project layout

```
spool/
├── docker-compose.yml               # trino-spool on the default port 8080
├── .env.example                     # documented shape -- safe to commit
├── .env                             # real values (600, gitignored)
├── .spool-secret                    # 256-bit base64 segment-signing key (600, gitignored)
├── mode.sh                          # renders a profile into etc/ and restarts
├── verify-bucket.sh                 # Step 0.3 preflight
├── trino-vast-5.5.0/                # connector, unzipped from the release URL
├── etc/
│   ├── node.properties, jvm.config, log.properties
│   ├── profiles/                    # templates with __PLACEHOLDER__ markers
│   ├── catalog/<catalog>.properties # rendered
│   ├── config.properties            # rendered -- the active profile
│   └── spooling-manager.properties  # rendered -- Profile B only
├── bench/Bench.java, bench/run-ab.sh
└── results/
```

`mode.sh` renders the templates at mode 644. That is deliberate: the
container's `trino` user does not map to the host uid that owns the files, so
600 makes Trino fail to start. The secrets themselves stay in `.env` and
`.spool-secret`, which are 600 and gitignored.

## Step 2 — Fetch the connector

```bash
curl -sSLO https://github.com/vast-data/vast-db-connectors/releases/download/trino-vast-5.5.0-478-e92442418427/trino-vast-5.5.0-478-e92442418427.zip
unzip -q -o trino-vast-5.5.0-478-e92442418427.zip
```

Confirm the build actually loaded once Trino is up. This log line is the only
reliable check that you are not silently running a stale plugin directory —
the version in the directory name proves nothing about what got loaded:

```bash
docker logs trino-spool 2>&1 | grep -o 'version_hash=[a-f0-9]*'
# version_hash=e9244241842707e5a1ab818487cd941e9460f132
```

## Step 3 — Generate the segment-signing key

`protocol.spooling.shared-secret-key` must be exactly 256 bits, base64:

```bash
openssl rand -base64 32 > .spool-secret && chmod 600 .spool-secret
```

Trino refuses to start with any other length. This key signs segment
identifiers so one client cannot forge a handle to another query's data. It is
not the bucket's encryption key.

## Step 4 — Profile A (baseline)

```bash
./mode.sh baseline
```

Renders `config.baseline.properties` with `protocol.spooling.enabled=false`
and **removes** `spooling-manager.properties`, so the baseline runs with no
spooling machinery loaded at all. Sanity check:

```bash
docker exec trino-spool trino --execute \
  'SELECT count(*) FROM var203."synthetic-datasets/tables".events'
```

## Step 5 — Measure Profile A

```bash
./bench/run-ab.sh baseline none
```

`--encoding none` leaves the JDBC `encoding` property unset. What makes this
a baseline is the *server* profile, not that flag — the driver would negotiate
spooling on its own if the server offered it (see Troubleshooting). Each
iteration opens a fresh connection,
drains all 100k rows, and calls `getObject` on every one of the 265 columns so
deserialization cost is genuinely paid rather than optimized away. Two warmups
are discarded — the first run is consistently a JIT/connection outlier — and
five are measured.

## Step 6 — Profile B (spooling)

```bash
./mode.sh spooling
```

Verify what the server actually resolved, rather than what you think you
wrote:

```bash
docker logs trino-spool 2>&1 | grep 'protocol.spooling'
```

Read the columns carefully: airlift prints `property  default  current
description`, so the second column is the **default**, not your setting. It is
easy to misread `enabled false` as your config when it is just the default
next to `current=true`.

Watch `protocol.spooling.inlining.max-rows` in particular. Its **default is
50,000**, which means a 100k-row result can be largely inlined per worker and
the test silently measures almost nothing while appearing to work. This
runbook pins it to 1,000.

## Step 7 — Measure Profile B

```bash
./bench/run-ab.sh spooling json json+lz4 json+zstd
```

## Step 8 — Confirm segments really transited S3

Wall-clock alone cannot distinguish spooling from inlining. Two independent
confirmations:

```bash
# 1. Objects appear under the spool prefix mid-query, then drain back to zero
#    as the client acknowledges them (fs.segment.explicit-ack=true).
watch -n2 'aws --endpoint-url "$S3_ENDPOINT" \
    s3 ls --recursive "s3://$SPOOL_BUCKET/$SPOOL_PREFIX/" | wc -l'

# 2. The coordinator's own output byte count collapses.
grep -o '"outputDataSize":"[^"]*"' results/*.tsv.stats.json
```

The second is definitive: `outputDataSize` is what the coordinator served to
the client.

### Checking from a Java JDBC client

There is no public JDBC API for this. `QueryStats` (via
`TrinoResultSet.getStats()`) carries 25 getters and none of them mention
spooling, segments, or the negotiated encoding, and `TrinoResultSet` itself
adds only `getQueryId`, `getStats`, and `getWarnings`. Nor does a mismatch
announce itself: a client requesting `encoding=json+zstd` against a server
with spooling **disabled** completes normally, with no `SQLException`, no
`SQLWarning`, and client-side stats identical to a spooled run. Enabling
`spooling_unsupported_warning` — as a session property and as
`protocol.spooling.unsupported-warning.enabled` server-side — produced no
client-visible warning in 478 either.

Two checks that do work:

```java
// (a) Is the SERVER capable? Pure JDBC, no side channel.
//     6 rows when protocol.spooling.enabled=true, 1 row when false.
try (ResultSet r = stmt.executeQuery("SHOW SESSION LIKE 'spooling%'")) {
    while (r.next()) System.out.println(r.getString(1) + " = " + r.getString(2));
}

// (b) Did THIS query spool? Take the query id the driver exposes, then read
//     the coordinator's own stats. outputDataSize is what the coordinator
//     served; if the rows went through object storage it collapses to the
//     size of the segment handles.
String queryId = rs.unwrap(io.trino.jdbc.TrinoResultSet.class).getQueryId();
// GET http://<coordinator>/v1/query/{queryId}
//   spooled : processedInputDataSize=245575378B  outputDataSize=3726B
//   inline  : processedInputDataSize=245575378B  outputDataSize=245575378B
```

Check (b) needs a result large enough to clear the inlining threshold
(`spooling_inlining_max_rows`, default 50,000). Below it, spooling is working
correctly and still reporting a large `outputDataSize`, because small results
are deliberately served inline.

---

## Results

Trino 478 · 100,000 rows × 265 columns (234 MiB) · median of 5 runs after 2 warmups

| Profile | Client encoding | Median total | Throughput | Speedup | Median TTFR | Coordinator `outputDataSize` | Coord CPU peak / mean |
|---|---|---|---|---|---|---|---|
| A — baseline | *(none)* | 48,598 ms | 2,058 rows/s | 1.0× | 1,428 ms | 245,575,378 B | 774% / 153% |
| B — spooling | `json` | **7,541 ms** | **13,262 rows/s** | **6.4×** | 1,423 ms | 3,487 B | 847% / 133% |
| B — spooling | `json+lz4` | 9,767 ms | 10,239 rows/s | 5.0× | 1,289 ms | 3,723 B | 371% / 89% |
| B — spooling | `json+zstd` | 14,548 ms | 6,874 rows/s | 3.3× | 1,675 ms | 3,726 B | 574% / 103% |

`processedInputDataSize` was 245,575,378 B in **every** run, so the scan side
of the query was identical throughout and the entire difference is in result
delivery.

### Reading the numbers

**The coordinator stops being the data path.** Its `outputDataSize` falls from
234 MiB to ~3.5 KB — a factor of ~70,000. Those 3.5 KB are segment handles;
the 234 MiB moved worker → VAST S3 → JDBC client without transiting the
coordinator. That, not the wall-clock figure, is the structural result. A
coordinator serving one such query at 234 MiB is a coordinator not serving
anyone else, and the effect compounds with concurrency in a way a
single-client A/B understates.

**Compression made things worse, monotonically.** Uncompressed `json` is
fastest; `lz4` is ~30% slower and `zstd` ~93% slower. Result bytes cross a
fast local network to a local VAST cluster, so segment transfer was never the
constraint — compression only adds CPU at the worker on write and at the JDBC
client on read. Choose `json` on a fat, low-latency path like this one. The
ordering should invert over a constrained or WAN link, which is the case the
compressed encodings exist for. This is a finding about *this* topology, not a
general ranking.

**Time-to-first-row is unchanged** (~1.3–1.7 s, within noise across all four
configurations). Spooling is a bulk-throughput mechanism; it does not make a
query start returning sooner. The 8 MB `initial-segment-size` is what keeps it
from making TTFR meaningfully *worse*.

**Coordinator CPU is the weakest measurement here.** Mean drops from 153% to
89–133% and the compressed encodings show clearly lower peaks, but `json`
peaked *higher* than the baseline (847% vs 774%). Two reasons to distrust the
CPU column: this is a single-node deployment, so
`node-scheduler.include-coordinator=true` means the coordinator is also the
only worker — it both serializes segments and executes the scan — and
`docker stats` sampled once per second is too coarse for a 7-second run. Treat
`outputDataSize` as the load evidence and the CPU figures as directional only.
On a cluster with dedicated workers the coordinator-side reduction would be
both sharper and cleanly attributable.

### Caveats

- Single-node Trino: the coordinator is also the only worker, so the CPU
  figures blend coordination with execution and understate the offload.
- Client, coordinator, and VAST cluster share one fast local network. This is
  the topology least favourable to the compressed encodings.
- One concurrent client. Coordinator offload is a concurrency-scaling
  property, and a single-client test cannot demonstrate it.
- `LIMIT 100000` without `ORDER BY` returns a non-deterministic 100k rows.
  Fine for timing — row count, column count, and byte count were identical in
  every run — but the runs do not read identical data.

---

## Troubleshooting

**`FileNotFoundException: /etc/trino/config.properties (Permission denied)`**
Rendered configs were mode 600 and the container's `trino` user does not map
to the host uid that owns them. `mode.sh` renders at 644 for this reason.

**`404 NoSuchKey … could not find @s3_bucket_name=ipv4-…`**
`data_endpoints` was set to per-CNode *hostnames* of the form
`ipv4-<ip>.main.<cluster>.<domain>`. The connector addresses data endpoints
virtual-host style, so the leading hostname label is parsed as a bucket name
and every read 404s. Use raw CNode IPs, which keep the bucket in the URI path.
This affects `data_endpoints` only — the metadata `endpoint` works fine as a
hostname.

**Spooling enabled but no objects ever appear in the bucket**
`protocol.spooling.inlining.max-rows` (default 50,000) is inlining the result.
Lower it, or test with a result well above the threshold.

**Segment objects accumulate and are never deleted**
The identity lacks `DeleteObjects` (bulk delete). The pruner deletes in
batches of `fs.segment.pruning.batch-size` and fails quietly without it.
Re-run `./verify-bucket.sh`.

**`AccessDenied` on `ListObjectsV2` for the table prefix**
Expected. The database prefix is served over the DATABASE protocol, not S3.
Only the spool prefix needs S3 rights.

**Trino will not start: `shared-secret-key must be 256 bits long`**
Regenerate with `openssl rand -base64 32` — not 16, not 64.

**Numbers look identical between profiles**
Check that the server profile actually changed. Toggling the *client* encoding
is not enough to produce a baseline — see below.

**Omitting the JDBC `encoding` property does not disable spooling**
The 478 driver negotiates a spooled encoding on its own whenever the server
offers one. Measured against a spooling-enabled server, a client that sets no
`encoding` property still spooled: 15.8 s with a 3,726 B coordinator
`outputDataSize`, against 48.6 s and 245,575,378 B with spooling disabled
server-side. So a real baseline requires `protocol.spooling.enabled=false` on
the server, which is what Profile A does.

Two consequences. An A/B that only varies the client property measures
nothing. And since the negotiated default behaves like `json+zstd` here — the
*slowest* of the three encodings on a fast network — leaving `encoding` unset
costs roughly half the available speedup. Set it explicitly.
