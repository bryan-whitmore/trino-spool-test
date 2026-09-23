#!/usr/bin/env bash
# Render one of the two A/B profiles into etc/ and restart Trino.
#
#   ./mode.sh baseline   Profile A -- protocol.spooling.enabled=false
#   ./mode.sh spooling   Profile B -- spooling to the configured S3 prefix
#
# Templates live in etc/profiles/ with __PLACEHOLDER__ markers; every real
# value comes from .env and .spool-secret, so no tracked file ever contains
# a credential, hostname or IP.
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-}"
case "$MODE" in
  baseline|spooling) ;;
  *) echo "usage: $0 {baseline|spooling}" >&2; exit 2 ;;
esac

[ -f .env ] || { echo ".env not found -- copy .env.example and fill it in" >&2; exit 1; }
set -a; . ./.env; set +a

[ -f .spool-secret ] || { echo ".spool-secret not found -- openssl rand -base64 32 > .spool-secret" >&2; exit 1; }
SECRET="$(cat .spool-secret)"

SPOOL_LOCATION="s3://${SPOOL_BUCKET}/${SPOOL_PREFIX}/"

render() {
  sed -e "s|__S3_ACCESS_KEY__|${S3_ACCESS_KEY}|g" \
      -e "s|__S3_SECRET_KEY__|${S3_SECRET_KEY}|g" \
      -e "s|__S3_ENDPOINT__|${S3_ENDPOINT}|g" \
      -e "s|__S3_REGION__|${S3_REGION}|g" \
      -e "s|__DATA_ENDPOINTS__|${DATA_ENDPOINTS}|g" \
      -e "s|__SPOOL_LOCATION__|${SPOOL_LOCATION}|g" \
      -e "s|__SHARED_SECRET__|${SECRET}|g" \
      "$1" > "$2"
  # Must stay world-readable: the container's 'trino' user does not map to
  # the host uid that owns these files. The secrets they carry are held
  # instead by .env / .spool-secret, which are 600 and gitignored.
  chmod 644 "$2"
}

mkdir -p etc/catalog
render etc/profiles/catalog.properties "etc/catalog/${CATALOG}.properties"
render "etc/profiles/config.${MODE}.properties" etc/config.properties

if [ "$MODE" = spooling ]; then
  render etc/profiles/spooling-manager.properties etc/spooling-manager.properties
else
  # Profile A must not even load a spooling manager, so the file is absent
  # rather than merely unreferenced.
  rm -f etc/spooling-manager.properties
fi

docker compose up -d --force-recreate trino-spool

printf 'waiting for trino-spool to become healthy'
for _ in $(seq 1 60); do
  case "$(docker inspect -f '{{.State.Health.Status}}' trino-spool 2>/dev/null)" in
    healthy) echo " -- ok (profile: $MODE)"; exit 0 ;;
    unhealthy) echo " -- UNHEALTHY"; docker logs --tail 40 trino-spool; exit 1 ;;
  esac
  printf .; sleep 5
done
echo " -- timed out"; docker logs --tail 60 trino-spool; exit 1
