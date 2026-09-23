#!/usr/bin/env bash
# Measurement pass for one profile. Samples coordinator CPU alongside each
# JDBC run, because the headline claim of the spooled protocol is that
# result bytes stop flowing through the coordinator -- that shows up as
# coordinator CPU, not only as wall-clock time.
#
#   ./bench/run-ab.sh baseline none
#   ./bench/run-ab.sh spooling json json+lz4 json+zstd
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:?usage: run-ab.sh <profile-label> <encoding>...}"; shift
ENCODINGS=("$@")

set -a; . ./.env; set +a

JDBC="${TRINO_JDBC_JAR:-$HOME/.m2/repository/io/trino/trino-jdbc/${TRINO_VERSION}/trino-jdbc-${TRINO_VERSION}.jar}"
[ -f "$JDBC" ] || { echo "JDBC driver not found: $JDBC (set TRINO_JDBC_JAR)" >&2; exit 1; }

# The schema contains a '/', so it must stay double-quoted in the SQL.
SQL="SELECT * FROM ${CATALOG}.\"${SCHEMA}\".${TABLE} LIMIT ${ROW_LIMIT}"
ITERS="${ITERS:-5}"
WARMUP="${WARMUP:-2}"

mkdir -p results

for ENC in "${ENCODINGS[@]}"; do
  LABEL="${PROFILE}-$(echo "$ENC" | tr '+' '-')"
  CPULOG="results/${LABEL}.cpu.log"

  # Sample container CPU every second for the duration of the run.
  ( while :; do
      docker stats --no-stream --format '{{.CPUPerc}}\t{{.MemUsage}}' trino-spool 2>/dev/null
    done ) > "$CPULOG" &
  SAMPLER=$!
  trap 'kill "$SAMPLER" 2>/dev/null || true' EXIT

  java -cp "$JDBC" bench/Bench.java \
    --url "jdbc:trino://localhost:8080/${CATALOG}" \
    --encoding "$ENC" --label "$LABEL" \
    --iters "$ITERS" --warmup "$WARMUP" \
    --sql "$SQL" --out "results/${LABEL}.tsv" 2>&1 | grep -vE '^WARNING|Picked up'

  kill "$SAMPLER" 2>/dev/null || true
  trap - EXIT

  awk -F'%' '{gsub(/ /,"",$1); if ($1+0>m) m=$1+0; s+=$1+0; n++}
       END {if (n) printf "   coordinator CPU: peak=%.0f%% mean=%.0f%% (%d samples)\n", m, s/n, n}' \
      "$CPULOG"
done
