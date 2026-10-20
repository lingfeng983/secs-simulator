#!/bin/sh
set -eu

: "${SECS_TOKEN:?SECS_TOKEN is required}"

exec java -cp "/app/lib/*" com.shimizukenta.secssim.runtime.Main \
  --mode server \
  --bind 0.0.0.0 \
  --port "${HTTP_PORT:-8080}" \
  --config "${SECS_CONFIG:-/app/demo.json}" \
  --web-root /app/web \
  --work-dir "${WORK_DIR:-/data}" \
  --token "$SECS_TOKEN" \
  "$@"
