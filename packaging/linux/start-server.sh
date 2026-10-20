#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${SECS_TOKEN:?SECS_TOKEN is required (set it in /etc/secs-sim.env)}"

exec "$ROOT/runtime/bin/java" -cp "$ROOT/lib/*" com.shimizukenta.secssim.runtime.Main \
  --mode server \
  --bind "${SECS_BIND:-0.0.0.0}" \
  --port "${HTTP_PORT:-8080}" \
  --config "${SECS_CONFIG:-$ROOT/demo.json}" \
  --web-root "$ROOT/web" \
  --work-dir "${WORK_DIR:-$ROOT/data}" \
  --token "$SECS_TOKEN" \
  "$@"
