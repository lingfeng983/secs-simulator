#!/usr/bin/env bash
# 启动运行时后端（Linux，JVM 形态）：server 模式对外提供服务。
#
# 与 Windows 原生包的 start-server.ps1 等价；本包不带界面，
# 远程机器上的界面用「连接设置」指向本机。
#
# 依赖：目标机已安装 JDK 25+（java 在 PATH 里）。
set -euo pipefail

PORT=8080
BIND=0.0.0.0
HSMS_PORT=5000
WORK_DIR="$(cd "$(dirname "$0")" && pwd)/work"

while getopts "p:b:h:w:" opt; do
  case "$opt" in
    p) PORT="$OPTARG" ;;
    b) BIND="$OPTARG" ;;
    h) HSMS_PORT="$OPTARG" ;;
    w) WORK_DIR="$OPTARG" ;;
    *) echo "用法: start-server.sh [-p API端口] [-b 绑定地址] [-h HSMS端口] [-w 数据目录]" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$WORK_DIR"

CP="$ROOT/secs-runtime.jar:$ROOT/secs-api-http.jar:$ROOT/secs-app.jar:$ROOT/secs-core.jar:$ROOT/secs-protocol.jar:$ROOT/secs-json.jar"

java -cp "$CP" com.shimizukenta.secssim.runtime.Main \
  --mode=server --bind "$BIND" --port "$PORT" --hsms-port "$HSMS_PORT" --work-dir "$WORK_DIR" &
SIM_PID=$!

trap 'kill "$SIM_PID" 2>/dev/null' EXIT

# 等 READY 行,取出 API 端口打给用户
for _ in $(seq 1 200); do
  sleep 0.05
  if READY=$(jobs -l >/dev/null; tail -c 65536 /proc/$SIM_PID/fd/2 2>/dev/null | grep -o '^READY .*$' | tail -1); then
    if [ -n "${READY:-}" ]; then break; fi
  fi
done

echo "$READY"
API_PORT=$(printf '%s' "$READY" | grep -oE 'http=[0-9]+' | cut -d= -f2)
echo ""
echo "API 地址: http://$(hostname):$API_PORT  （远程界面在「连接设置」里填它）"
echo "HSMS 端口: $HSMS_PORT   数据目录: $WORK_DIR"
wait "$SIM_PID"
