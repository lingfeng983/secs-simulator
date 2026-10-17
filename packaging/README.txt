SECS Simulator
==============

启动
----
双击 start.cmd（或右键「使用 PowerShell 运行」start.ps1）。
脚本会拉起模拟器、读取启动时的 token，并自动打开浏览器界面。

默认启动一个两腔体演示会话，SECS 端口 5000（HSMS-SS 被动）。

想用自己的设备配置
------------------
start.ps1 目前固定用 --demo-session。要装载配置文件，直接用命令行：

    secs-sim.exe --mode=integrated --config 你的设备.json --web-root web

配置格式见 docs-schema-v4.md。

界面
----
- 会话：状态、单元数、已上报 / 限流丢弃 / 禁用跳过 / S6F1 计数
- 曲线预览：可选会话与 1/5/15 分钟窗口（min/max 包络 + 均值线）
- 报文：实时收发的 SECS 报文（SML 文本）
- 手动触发：填 CEID 后点「上报」，主机会收到一条 S6F11
- 报警：填 ALID / 类别 / 文本后「置位」或「清除」，主机会收到 S5F1（等待 S5F2）

目录
----
    secs-sim.exe       原生可执行文件（无需安装 JVM）
    web/               前端静态资源
    start.ps1/start.cmd
    README.txt
    docs-schema-v4.md  配置格式说明
