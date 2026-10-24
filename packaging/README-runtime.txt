SECS Simulator — 运行时后端（server 形态）

这是一台机器上的"设备运行时"。它不带界面：界面来自另一台机器上的
完整安装包（secs-sim-<版本>-win-x64.zip），通过「连接设置」指向本机。

快速开始
--------
1. 双击 start-server.cmd（或运行 start-server.ps1）。
   默认：API 端口 8080（绑 0.0.0.0）、HSMS 端口 5000、数据目录 .\work。
2. 控制台会打印 API 地址（http://<本机名>:8080）。
3. 在装有完整安装包的机器上打开界面，顶栏「连接」→ 填这个地址 → 保存并重载。
   之后配置编辑、批量建设备、曲线、报文都直接操作这台远端。

安全提示
--------
默认为内部工具的开放访问（无凭据）。若部署在不受信任的网段，启动时加：
    start-server.ps1 -Mode 参数不支持凭据时，用命令行：
    secs-sim.exe --mode=server --bind 0.0.0.0 --port 8080 --token <你的口令>
界面连接时后端会要求凭据（当前安装包界面版本默认不带令牌输入，如需受限模式请用 API/脚本访问）。
生产建议置于 TLS 反向代理之后。

常用参数（start-server.ps1）
--------
    -Port 8080        API 端口
    -Bind 0.0.0.0     绑定地址
    -HsmsPort 5000    默认 SECS 端口（配置文件未指定端口时用）
    -WorkDir .\work   设备配置与日志目录（一文件一设备，格式见 docs-schema-v5.md）

数据
--------
work\         设备配置（*.json）与 logs\（app.log / messages.jsonl / audit.jsonl）
会话的配置在启动那一刻读入内存；改配置后要在界面「会话」面板点「重载」才生效。

版本：<版本>
