SECS Simulator — Linux 服务端（<版本>）
========================================

这是部署在 Linux 机器上的设备模拟后端，以 JVM（jar 包）方式运行。
它被 Windows 机器上安装的 SECS Simulator 远程操作：Windows 端在
「连接服务器」里填本机地址，界面即由本服务端提供。

快速开始
--------
1. 目标机需 JDK 25+（java 在 PATH 里）。
2. 运行 ./start-server.sh
   默认：API 端口 8080（绑 0.0.0.0）、HSMS 端口 5000、数据目录 ./work。
3. 控制台会打印 API 地址（http://<本机名>:8080）。
4. 在 Windows 机器上安装 SECS Simulator，启动后在「连接服务器」
   标签页填上面的地址，点「连接服务器」。

常用参数（start-server.sh）
--------
    -p 8080          API 端口
    -b 0.0.0.0       绑定地址
    -h 5000          HSMS 端口（配置文件未指定端口时用）
    -w ./work        数据目录（设备配置与日志，一文件一设备）

安全提示
--------
默认开放访问（内网工具场景，无凭据）。若部署在不受信任的网段，绕过
start-server.sh 直接用命令行启动并加凭据：

    java -cp 'secs-runtime.jar:secs-api-http.jar:secs-app.jar:secs-core.jar:secs-protocol.jar:secs-json.jar' \
      com.shimizukenta.secssim.runtime.Main \
      --mode=server --bind 0.0.0.0 --port 8080 --hsms-port 5000 \
      --web-root web --work-dir work --token <你的口令>

注意：加了 --token 后当前界面无法登录（界面为免凭据形态），受限模式请配合
API/脚本访问。生产建议置于 TLS 反向代理之后。

目录
--------
    secs-*.jar        后端模块（runtime / api-http / app / core / protocol / json）
    web/              前端静态资源（服务端托管，供远程界面加载）
    start-server.sh   启动脚本
    docs-schema-v5.md 设备配置格式说明
    VERSION           版本号

数据
--------
work/          设备配置（*.json）与 logs/（app.log / messages.jsonl / audit.jsonl）
会话的配置在启动那一刻读入内存；改配置后在界面里「重载」该会话生效。

故障排查
--------
- 提示 java 找不到：安装 JDK 25+ 并确认 java 在 PATH。
- Windows 端连不上：检查防火墙放行 API 端口；本机用
  curl http://127.0.0.1:8080/api/v1/health 自检。
- 端口被占用：-p 换一个 API 端口。
