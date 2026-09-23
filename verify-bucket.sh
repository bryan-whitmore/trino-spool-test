#!/usr/bin/env bash
# Step 0.3 preflight: prove the spool prefix supports every operation the
# filesystem spooling manager performs. A missing right here does not fail
# at startup -- it fails mid-query, or silently leaks segment objects -- so
# this is checked up front rather than discovered later.
set -uo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo ".env not found -- copy .env.example and fill it in" >&2; exit 1; }
set -a; . ./.env; set +a

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

aws_() {
  docker run --rm \
    -e AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" \
    -e AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    -e AWS_DEFAULT_REGION="$S3_REGION" \
    -v "$WORK:/w" \
    amazon/aws-cli:2.36.24 --endpoint-url "$S3_ENDPOINT" "$@"
}

FAILED=0
check() { # check <name> <command...>
  local name="$1"; shift
  if "$@" >"$WORK/out" 2>&1; then
    printf '  ok      %s\n' "$name"
  else
    printf '  FAILED  %s\n' "$name"
    sed 's/^/            /' "$WORK/out" | tail -5
    FAILED=1
  fi
}

echo "verifying s3://$SPOOL_BUCKET/$SPOOL_PREFIX/"

echo "probe" > "$WORK/small.txt"
# 24 MiB exceeds the CLI's multipart threshold, exercising the chunked
# upload path the spooling manager uses for larger segments.
dd if=/dev/urandom of="$WORK/big.bin" bs=1M count=24 status=none

BASE="s3://$SPOOL_BUCKET/$SPOOL_PREFIX/.preflight"

check "PutObject"            aws_ s3 cp /w/small.txt "$BASE/small.txt"
check "ListBucket (prefix)"  aws_ s3 ls "$BASE/"
check "GetObject"            aws_ s3 cp "$BASE/small.txt" /w/back.txt
check "Multipart PutObject"  aws_ s3 cp /w/big.bin "$BASE/big.bin"

# retrieval-mode=STORAGE hands the JDBC client a signed URL, so the URL must
# work with no credentials attached at all.
URL="$(aws_ s3 presign "$BASE/big.bin" --expires-in 300 2>/dev/null | tr -d '\r')"
if [ -n "$URL" ] && [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL")" = 200 ]; then
  printf '  ok      Pre-signed GetObject (unauthenticated)\n'
else
  printf '  FAILED  Pre-signed GetObject (unauthenticated)\n'
  FAILED=1
fi

check "DeleteObject"         aws_ s3 rm "$BASE/small.txt"
check "DeleteObjects (bulk)" aws_ s3api delete-objects --bucket "$SPOOL_BUCKET" \
      --delete "Objects=[{Key=$SPOOL_PREFIX/.preflight/big.bin}]"

if [ "$FAILED" -eq 0 ]; then
  echo "all checks passed -- bucket is ready for spooling"
else
  echo "one or more checks failed -- fix before running Profile B" >&2
fi
exit "$FAILED"
