# trino-spooling-vast

An A/B benchmark of the **Trino 478 spooled client protocol** against the
classic client protocol, with a VAST S3 bucket as the spool store and VAST
Database as the data source.

The spooled protocol has workers write encoded result segments straight to
object storage; the coordinator returns only segment handles and the JDBC
client fetches the bytes itself from pre-signed URLs. This repo measures what
that is worth.

**Headline result** — 100,000 rows × 265 columns (234 MiB), single-node Trino:

| Profile | Client encoding | Median | Speedup | Coordinator `outputDataSize` |
|---|---|---|---|---|
| baseline | *(none)* | 48,598 ms | 1.0× | 245,575,378 B |
| spooling | `json` | **7,541 ms** | **6.4×** | 3,487 B |
| spooling | `json+lz4` | 9,767 ms | 5.0× | 3,723 B |
| spooling | `json+zstd` | 14,548 ms | 3.3× | 3,726 B |

The wall-clock number is not the interesting part. The coordinator's output
byte count falls ~70,000× while its *input* byte count is unchanged — the
result data stopped flowing through the coordinator at all. Compression made
things worse here because the network was never the constraint; that should
invert over a WAN link.

## Prerequisites

- Docker with Compose
- Java 17+ (for the JDBC benchmark harness)
- A VAST cluster with an S3 bucket the benchmark identity can fully manage —
  see Step 0 of [RUNBOOK.md](RUNBOOK.md) for the exact rights, which include
  bulk delete and unauthenticated pre-signed GET
- The Trino JDBC driver:
  ```bash
  curl -sSLO https://repo1.maven.org/maven2/io/trino/trino-jdbc/478/trino-jdbc-478.jar
  export TRINO_JDBC_JAR=$PWD/trino-jdbc-478.jar
  ```

## Clone to first run

```bash
# 1. Configure. Every cluster-specific value lives here; nothing tracked in
#    this repo contains a credential, hostname or IP.
cp .env.example .env && chmod 600 .env && $EDITOR .env

# 2. Generate the 256-bit segment-signing key (Trino rejects any other length).
openssl rand -base64 32 > .spool-secret && chmod 600 .spool-secret

# 3. Fetch the VAST connector. It is not vendored, so this step is required
#    before the container will start.
curl -sSLO https://github.com/vast-data/vast-db-connectors/releases/download/trino-vast-5.5.0-478-e92442418427/trino-vast-5.5.0-478-e92442418427.zip
unzip -q -o trino-vast-5.5.0-478-e92442418427.zip

# 4. Prove the bucket supports every operation spooling needs. Do this before
#    anything else -- a missing right fails mid-query, not at startup.
./verify-bucket.sh

# 5. Run both halves of the A/B.
./mode.sh baseline  && ./bench/run-ab.sh baseline none
./mode.sh spooling  && ./bench/run-ab.sh spooling json json+lz4 json+zstd
```

Results land in `results/` as TSVs plus the coordinator's own query stats.

## Layout

| Path | Purpose |
|---|---|
| [RUNBOOK.md](RUNBOOK.md) | The full procedure, results discussion, and troubleshooting |
| `mode.sh` | Renders a profile into `etc/` and restarts Trino |
| `verify-bucket.sh` | Preflight for the seven S3 operations spooling requires |
| `etc/profiles/` | Config templates with `__PLACEHOLDER__` markers |
| `bench/Bench.java` | JDBC harness — times a full result drain, touching every cell |
| `bench/run-ab.sh` | Measurement pass with coordinator CPU sampling |

`.env` and `.spool-secret` hold the real values and are gitignored, along with
the rendered config under `etc/` and the contents of `results/`.

## Caveats

Single-node Trino, one concurrent client, and a fast local network. The
coordinator-offload benefit is a concurrency-scaling property that a
single-client test cannot demonstrate, and the CPU figures blend coordination
with execution because the coordinator is also the only worker. See the
Caveats section of [RUNBOOK.md](RUNBOOK.md) before generalizing any of this.
