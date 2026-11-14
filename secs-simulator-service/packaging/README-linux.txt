SECS Simulator — Linux 服务端（<版本>）
========================================

这是部署在 Linux 机器上的设备模拟后端，以 JVM（jar 包）方式运行。
它被 Windows 机器上安装的 SECS Simulator 远程操作：Windows 端在
右上角「连接」里填本机地址即可，配置、曲线、报文全部操作本机。

快速开始
--------
1. 目标机需 JDK 25+（java 在 PATH 里）。
2. 运行 ./start-server.sh
   默认：API 端口 8080（绑 0.0.0.0）、HSMS 端口 5000、数据目录 ./work。
3. 控制台会打印 API 地址（http://<本机名>:8080）。
4. 在 Windows 机器上安装 SECS Simulator，打开后点右上角「连接」，
   填上面的地址保存即可（本机浏览器也可以直接打开这个地址用）。

常用参数（start-server.sh）
--------
    -p 8080          API 端口
    -b 0.0.0.0       绑定地址
    -h 5000          HSMS 端口（配置文件未指定端口时用）
    -w ./work        数据目录（设备配置与日志，一文件一设备）

安全提示
--------
本工具不做鉴权：没有令牌、账号或登录，所有端点直连可调用（不会回 401/403）。
start-server.sh 默认绑 0.0.0.0 只为现场联调方便；若部署在不受信任的网段，请用
防火墙/网段隔离限制来源，必要时在反向代理上加 TLS 与访问控制。

只想改绑定地址可以不改脚本：

    ./start-server.sh -b 127.0.0.1

等价的手工启动命令（排查启动问题时对照用）：

    java -cp 'secs-cli.jar:secs-runtime.jar:secs-api-http.jar:secs-app.jar:secs-core.jar:secs-protocol.jar:secs-json.jar' \
      com.shimizukenta.secssim.cli.CliMain \
      --mode=server --bind 127.0.0.1 --port 8080 --hsms-port 5000 \
      --web-root web --work-dir work

目录
--------
    secs-*.jar        后端模块（cli / runtime / api-http / app / core / protocol / json）
    web/              前端静态资源（服务端托管，供本机浏览器直接打开；
                      Windows 桌面端界面内嵌在安装程序里，不依赖它）
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
