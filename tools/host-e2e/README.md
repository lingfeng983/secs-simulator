# 实机 E2E 工具（host-e2e）

独立的 Java 协议探针与浏览器实机验证脚本。**不属于任何 Maven 模块**，按需手工编译运行。

| 文件 | 作用 |
|---|---|
| `SimHost.java` | 模拟真实 HSMS 主机：S1F1/S1F3/S1F13/S6F11/S10F3 等全链路 + HTTP 接口断言；退出码 = 失败项数 |
| `MinProbe.java` | 最小 HSMS 探针：只应答 S1F1，用于端口/连通性排障 |
| `run-e2e.ps1` | 一键：启动原生 exe → Playwright 实机 E2E → SimHost 全量 → 优雅停机；末行输出 `RESULT=PASS/FAIL` |
| `run-probe.ps1` | 一键：启动原生 exe → MinProbe 排障 |

前置条件：先构建原生镜像（`mvn -B -Pnative -pl secs-cli -am -DskipTests package`）与前端（`scripts/build-frontend.ps1`），
并设置 `JAVA_HOME`（脚本用 `$env:JAVA_HOME\bin\javac.exe` 编译）。

编译与运行（在仓库根执行）：

```powershell
$cp = 'secs-protocol\target\classes;secs-json\target\classes'
& "$env:JAVA_HOME\bin\javac.exe" -cp $cp -d tools\host-e2e tools\host-e2e\SimHost.java
& "$env:JAVA_HOME\bin\java.exe" -cp "tools\host-e2e;$cp" SimHost <httpPort> <hsmsPort> <deviceId>
```

`*.class` 与 `*.log` 是本目录的编译/运行产物，不入库（见 `.gitignore`）。
