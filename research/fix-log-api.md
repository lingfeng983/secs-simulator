# API 契约缺口修复日志（fix-log-api）

- 日期：2026-11-24
- 依据：`research/code-review-2026-11-24_dim06.md`（C1-C5、P3-1 及问题清单）
- 改动范围（仅限授权文件）：
  - `secs-simulator-service/scripts/openapi.yaml`
  - `secs-simulator-service/scripts/check-openapi.py`
  - `secs-simulator-service/scripts/check.ps1`
  - `secs-simulator-service/scripts/openapi-paths.txt`（新建）
  - `secs-simulator-service/secs-api-http/src/main/java/com/shimizukenta/secssim/api/http/HttpApiServer.java`

## 逐条状态

| # | 项 | 级别 | 状态 | 说明 |
|---|---|---|---|---|
| 1 | `DELETE /api/v2/devices/{id}` 漏声明（C1） | P1 | ✅ 已补 | 读实现 DeviceRuntimeHandler.java:95-106 后声明：200 `{"ok":true,"destroyed":true}`、404 `device-not-running`、502 `destroy-failed`；description 说明"销毁会话不删实体"，与 link/disconnect、DELETE configs 区分。新 path 插在 `/api/v2/devices/{id}/config/revisions` 之前 |
| 2 | `POST /api/v2/devices/{id}/apply-sections` 漏声明（C2） | P1 | ✅ 已补 | 读 DeviceRuntimeHandler.java:178-212 + RuntimeApi.java:775-829 后声明：请求体 `{"sections":[...]}`；200（effect/changed/reset/preserved/plansKept/pinsDropped，空 sections 幂等）、400 bad-body/bad-section、404 unknown-session、405、409 not-reloadable/not-running/reconnect-failed、422（ConfigProblems）、500 apply-failed |
| 3 | `PUT /api/v1/configs/{name}/sections` 漏声明（C3） | P1 | ✅ 已补 | 读 ConfigHandler.java:399-409、548-595 + RuntimeApi.java:670-758 后声明：请求体 `{"sections":{...}}`；If-Match 头（modifiedMs，不一致 409 revision-conflict；非法值按"不检查"处理，与 ifMatch() 实现一致）、`?confirmOverwrite=1/true/空值` 查询参数（守卫 C 二次确认）；200（revision/modifiedMs/changed/effect/warnings，无变化幂等不 +1 版本）、400、404、405、409 三类语义（revision-conflict / conflicts 守卫 B / ecv-overwrite+warnings）、422、500。按 2026-11-21 实现注明结构段可随时保存（无 not-offline 守卫） |
| 4 | `GET/POST /api/v1/host-sims/{hostId}/burst` 漏声明（C4） | P1 | ✅ 已补 | 读 HostSimHandler.java:219-280 后参照会话级 `/sessions/{id}/burst`（BurstStatus/BurstResult schema）声明：GET 200/404 unknown-host；POST 202/400/404/405/409 burst-running|burst-busy/500（burstStatusOf 解析失败回 500）；请求体 schema 与会话级同形 |
| 5 | `DELETE /api/v1/sessions/{id}` 语义过时（C5/P2-1） | P2 | ✅ 已改 | 读 SessionHandler.java:289-328 后：summary 改"断开设备链路（3.0 起不再移除会话；废弃兼容别名）"，补 `deprecated: true`、description（断链不删实体、内部转 v2 link/disconnect）；200 声明 Deprecation/Sunset 响应头（Sunset 实现值 Wed, 30 Jun 2027 00:00:00 GMT，SessionHandler.java:33）与 body `{ok, phase:DISCONNECTED, hint}`；补 400 bad-session-id、404 unknown-session |
| 6 | v1 alarms / terminal 补 deprecated | P2 | ✅ 已核对，无需改动 | 读取 openapi.yaml 现文（/alarms 与 /terminal 段）确认两条均已带 `deprecated: true` + Deprecation/Sunset 说明与替代端点/hint 描述（2026-11-18 审计批次二已落地，评审报告第 44 行也确认）。本次未重复修改 |
| 7 | 契约闸门（防复发） | P1 建议 | ✅ 已建 | ① 新建 `scripts/openapi-paths.txt`：75 条 path 排序清单（71 旧 + 4 新）。② `check-openapi.py` 新增默认快照比对（不一致即 FAIL，逐条列出新增/删除端点并提示 `--snapshot` 同步）与 `--snapshot` 再生成模式；新增注册前缀覆盖检查：从 `ApiPaths.java` 正则解析全部 `/api/` 常量值，path 参数段替换后须落在某常量前缀下（HttpServer 最长前缀匹配语义，`xxx/` 前缀 startsWith、无尾斜杠常量要求等值或 `常量+"/"`），挡整条死声明。原有重复键/$ref/无操作检查保留。③ `check.ps1` 的 openapi 步骤注释与步骤名标明含快照校验（调用方式不变，默认即校验） |
| 8 | HttpApiServer 私有 respond 不写 LAST_STATUS（P3-1） | P3 | ✅ 已修 | HttpApiServer.java 私有 `respond` 改为委托 `HttpSupport.respond`（后者写 LAST_STATUS），health/shutdown/静态资源/慢池 503/兜底 500 的访问日志状态码不再恒 0，附注释说明成因。`json()` 与 HttpSupport 重复（P3-6）未动（超范围小项） |
| — | 其余 P3（P3-2/3/4/5/6/7） | P3 | ⏭️ 按任务指示跳过 | 仅记录，未改动 |

## 验证结果

1. **openapi 校验**（含新闸门）：
   - `"$BOX_AGENT_PYTHON" secs-simulator-service/scripts/check-openapi.py --snapshot` → 生成快照 75 个 path；
   - `"$BOX_AGENT_PYTHON" secs-simulator-service/scripts/check-openapi.py` → `[OK] …: 75 个 path，$ref 全部可解析，快照一致，注册前缀全覆盖`（exit 0）。
2. **闸门负向测试**（临时变异副本置于会话 scratch，未动仓库文件）：
   - 改名一个 path（模拟漏同步）→ exit 1，报"快照缺少新增端点/多出已删端点/不一致提示"；
   - 注入 `/api/v9/ghost` 死声明 → exit 1，报"不在任何注册前缀下（疑似死声明）"。两例均按预期挡下。
3. **Java 构建/测试**：`cd secs-simulator-service && JAVA_HOME='E:\Java\graalvm-jdk-25.0.4+7.1' mvn -B -pl secs-api-http -am test` → **BUILD SUCCESS**，secs-api-http `Tests run: 143, Failures: 0, Errors: 0, Skipped: 0`（全 reactor 6 模块 SUCCESS）。

## 风险与未覆盖项

- 新声明的响应形状均逐行取自实现（Handler + RuntimeApi），但 openapi 只做静态校验，未做逐端点运行时契约测试；机器比对闸门只覆盖 path 级（快照 + 前缀），不校验方法/响应码级漂移（评审建议的完整比对超出本轮范围）。
- `apply-sections` 的 200 schema 中 `effect` 枚举未穷举（实现由 ConfigSection.effectOf 决定），仅描述 hot/reconnect 等示例。
- check.ps1 未整脚本执行（其 mvn -B test 为全模块，超出本轮验证预算）；其 openapi 步骤等价命令已单独验证通过。
- P3-2~P3-7 未处理（任务指示跳过）；C6（v1 message 502 声明核对）本次读取 openapi.yaml:337-350 确认响应段声明了 200/400/404/409/500 而无 502——实现 IOException → 502 send-failed（评审引 SessionHandler.java:519-523），该缺口仍存在，属评审"待核对"项而非本轮必修清单，留待下轮。