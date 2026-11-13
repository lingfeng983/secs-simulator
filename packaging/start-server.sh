#!/usr/bin/env bash
# 启动 Linux 服务端（JVM 形态，server 模式）：被 Windows 安装程序远程操作的后端。
#
# 本包自带 web/（前端静态资源），服务端会把它与 API 一起托管；
# Windows 机器上安装的 SECS Simulator 在「连接服务器」里填地址即可（内部工具，直连）。
#
# 依赖：目标机已安装 JDK 25+（java 在 PATH 里）。
#
# 访问控制：不做鉴权——没有令牌、没有账号文件；默认绑 0.0.0.0，
# 对外暴露范围请用防火墙/网段控制，必要时叠加 TLS。
set -euo pipefail

PORT="${PORT:-8080}"
BIND="${BIND:-0.0.0.0}"
HSMS_PORT="${HSMS_PORT:-5000}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK_DIR="${WORK_DIR:-$ROOT/work}"
WEB_ROOT="$ROOT/web"

while getopts "p:b:h:w:" opt; do
  case "$opt" in
    p) PORT="$OPTARG" ;;
    b) BIND="$OPTARG" ;;
    h) HSMS_PORT="$OPTARG" ;;
    w) WORK_DIR="$OPTARG" ;;
    *) echo "用法: start-server.sh [-p API端口] [-b 绑定地址] [-h HSMS端口] [-w 数据目录]" >&2; exit 2 ;;
  esac
done

mkdir -p "$WORK_DIR" "$WORK_DIR/logs"
LOG="$WORK_DIR/logs/server.out"

CP="$ROOT/secs-cli.jar:$ROOT/secs-runtime.jar:$ROOT/secs-api-http.jar:$ROOT/secs-app.jar:$ROOT/secs-store.jar:$ROOT/secs-core.jar:$ROOT/secs-protocol.jar:$ROOT/secs-json.jar:$ROOT/thirdparty/sqlite-jdbc.jar"

# 内部工具不做鉴权：浏览器/脚本直连地址即可，没有令牌或账号
java -cp "$CP" com.shimizukenta.secssim.cli.CliMain \
  --mode=server --bind "$BIND" --port "$PORT" --hsms-port "$HSMS_PORT" \
  --web-root "$WEB_ROOT" --work-dir "$WORK_DIR" \
  >>"$LOG" 2>&1 &
SIM_PID=$!

trap 'kill "$SIM_PID" 2>/dev/null || true' EXIT

# 等握手文件（后端原子写入 <work-dir>/ready.txt），最多 60 秒。
READY_FILE="$WORK_DIR/ready.txt"
rm -f "$READY_FILE"
for _ in $(seq 1 120); do
  if [ -s "$READY_FILE" ]; then
    break
  fi
  if ! kill -0 "$SIM_PID" 2>/dev/null; then
    echo "后端启动失败，最近日志：" >&2
    tail -n 50 "$LOG" >&2 || true
    exit 1
  fi
  sleep 0.5
done

if [ ! -s "$READY_FILE" ]; then
  echo "后端 60 秒内没有就绪，最近日志：" >&2
  tail -n 50 "$LOG" >&2 || true
  kill "$SIM_PID" 2>/dev/null || true
  exit 1
fi

API_PORT="$(grep -E '^http=' "$READY_FILE" | cut -d= -f2)"
HOST_NAME="$(hostname)"

echo ""
echo "API 地址: http://$HOST_NAME:${API_PORT:-$PORT}"
echo "HSMS 端口: $HSMS_PORT   数据目录: $WORK_DIR   日志: $LOG"
echo ""
echo "（Windows 端「连接」里填这个地址即可；内部工具直连，无需令牌）"
echo ""

wait "$SIM_PID"
