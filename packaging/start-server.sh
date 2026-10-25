#!/usr/bin/env bash
# 启动 Linux 服务端（JVM 形态，server 模式）：被 Windows 安装程序远程操作的后端。
#
# 本包自带 web/（前端静态资源），服务端会把它与 API 一起托管；
# Windows 机器上安装的 SECS Simulator 在「连接服务器」里填本机地址即可，
# 界面直接由本服务端提供，配置编辑、曲线、报文全部操作本机。
#
# 依赖：目标机已安装 JDK 25+（java 在 PATH 里）。
set -euo pipefail

PORT=8080
BIND=0.0.0.0
HSMS_PORT=5000
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK_DIR="$ROOT/work"
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

mkdir -p "$WORK_DIR"

CP="$ROOT/secs-runtime.jar:$ROOT/secs-api-http.jar:$ROOT/secs-app.jar:$ROOT/secs-core.jar:$ROOT/secs-protocol.jar:$ROOT/secs-json.jar"

java -cp "$CP" com.shimizukenta.secssim.runtime.Main \
  --mode=server --bind "$BIND" --port "$PORT" --hsms-port "$HSMS_PORT" \
  --web-root "$WEB_ROOT" --work-dir "$WORK_DIR" &
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
echo "API 地址: http://$(hostname):$API_PORT  （Windows 端「连接服务器」填它）"
echo "HSMS 端口: $HSMS_PORT   数据目录: $WORK_DIR"
wait "$SIM_PID"
