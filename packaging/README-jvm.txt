SECS Simulator（JVM 兜底包）
==============================

这是不依赖原生镜像的兜底包：自带 jlink 生成的 Java 运行时，
目标机器不需要预装 JVM。

启动
----
双击 start-jvm.cmd。脚本会拉起模拟器、读取 token 并打开浏览器。

默认演示会话 + HSMS 端口 5000。加载自己的配置：

    runtime\bin\java.exe -cp "lib\*" com.shimizukenta.secssim.runtime.Main `
      --mode=integrated --config 你的设备.json --web-root web

目录
----
    runtime/            jlink 运行时（含 java.exe）
    lib/*.jar           应用 jar（含协议/core/app/runtime）
    web/                前端静态资源
    start-jvm.ps1/cmd
    README-jvm.txt
    docs-schema-v5.md

与原生包的区别
--------------
- 启动稍慢，包体更大；
- 不依赖 MSVC / 原生镜像工具链，适合原生包无法运行的机器；
- 配置、HTTP API、前端与原生包完全一致。
