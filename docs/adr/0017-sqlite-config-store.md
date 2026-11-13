# ADR 0017 · 配置存储改用 SQLite（取代 ADR 0015 §4）

- 状态：已接受（3.0.0 落地，M0–M4 全部完成并验收）
- 日期：2026-11-12
- 影响范围：新增 `secs-store` 模块；`secs-app` / `secs-api-http` / `secs-runtime` / `secs-cli`；打包脚本；`NativeRuntimeIT`；前端配置相关 API
- 关联：[3.0 重新设计说明书](../design/redesign-2026-11-12.md) §2.2–§2.3
- 取代：ADR 0015 §4「SQLite：不做」。ADR 0015 其余结论（不做 Jetty / 不做签名 / 不做 Spooling）继续有效。

## 问题

需求要求「所有的配置信息使用 SQLite 数据库进行管理」，并要求设备/主机的「关闭/断开」不再删除实体。当前配置散落在 7 处（`<work-dir>/*.json`、`templates/*.json`、Java 源码里的内置模板、classpath 默认配置、顶层 `template` 标记、`equipment.constants`、以及一批纯内存的运行时编辑），且「删除会话 = 删除实体」。

把实体做成持久对象后，配置必须有一个能承载**事务、版本、并发读、结构化查询**的单一事实源；继续用「一个设备一个 JSON 文件 + mtime 乐观锁」无法支撑版本历史、配置漂移比对、结构化编辑与执行台账。

ADR 0015 当时否决 SQLite 的具体理由是：

> 报文日志与审计是「追加 + 定期轮转」的形态，现有文件实现已经够用。引入 sqlite-jdbc 会带来 JNI 依赖，直接破坏单文件原生镜像的价值。

这条判断对**日志**成立，对**配置**不成立：本 ADR 不把日志搬进 SQLite，只把配置与运行态台账搬进去，两者的收益/成本完全不同。

## 决策

### 1. 两个 SQLite 文件，物理隔离配置态与运行态

- `<work-dir>/config.db`：设备实体、配置原文、版本快照、结构化投影、模板、主机实体。**权威、可备份、有 revision**。
- `<work-dir>/runtime.db`：链路相位（`device_link` / `host_link`）、计划启用态与计数、执行台账、钉值、运行时计划正文与目录编辑（`runtime_plan_override` / `runtime_catalog_edit`）、主机动态报告、链路事件（`link_event`）。**进程启动时可清空重建**；`EventEffectStore` 的效果叠加层刻意不持久化（与「曲线是时间纯函数」冲突，重连后按当前时刻重算）。

跨库不做事务，靠 `device_id` 弱关联 + 启动对账收敛。日志（`messages.jsonl` / `audit.jsonl`）保持文件，不动 ADR 0009 / 0012。

### 2. 配置原文仍是唯一权威，结构化表是投影

`config_document.body` 保存原始 JSON 文本，逐字节保真，延续 ADR 0008 的编辑器契约（模型往返会静默丢掉用户手写的键）。`connection_spec / unit_def / svid_def / event_plan / ...` 全部是**由正文重算的投影**，只由 `reproject(body)` 在写事务里整体重建，禁止单表写入。结构化编辑先改正文子树再重投影，与现有 README 已声明的行为一致（结构化编辑会重排成 2 空格缩进、未知键保留）。

`modifiedMs` / `If-Match` 换成单调递增的 `revision` / `If-Revision`；**使用者发起的**每次写入留一份 `config_revision` 快照，支持差异对比与回滚（回滚生成新 revision，不覆盖历史）。快照按设备保留最近 200 版，超出按时间裁剪。

例外见 §5：S2F15 写设备常量同样升 revision，但**不写快照、不产生配置漂移**。

### 3. 用 `org.xerial:sqlite-jdbc`（3.47+），保留原生镜像

**前置现状（决定了这不是「加个依赖」而是「补一整条原生链路」）**：

| 项 | 现状 |
|---|---|
| GraalVM 反射 / 资源元数据 | 全仓**没有任何** `reachability-metadata.json` / `reflect-config.json` |
| 原生构建参数 | `secs-cli/pom.xml:70` 只有 `-H:IncludeResources=defaults/.*` |
| JNI 依赖 | **零**。sqlite-jdbc 会是本仓库第一个 |
| 夜间任务 | `.github/workflows/native-nightly.yml:21-22` 只跑 `windows-latest`，且 `continue-on-error: true`（失败不阻断） |
| `NativeRuntimeIT` | 覆盖 API / SSE 票据 / HSMS S1F1·S1F3 / 优雅停机，**没有任何数据库断言** |

因此本决策带一个**硬闸门 M0**：先做尖峰验证，通过了才允许进入存储地基阶段。

- WAL + `foreign_keys=ON` + `busy_timeout=5000`；单写者（`ReentrantLock` + 专用虚拟线程），读用独立连接。
- 同一个 `--work-dir` 不允许两个进程（与现有日志约束一致），启动时对 `config.db` 加文件锁，抢不到直接失败。
- **在线备份用 `VACUUM INTO`**（或 sqlite 在线备份 API），不直接复制 `config.db` 文件：WAL 模式下未 checkpoint 的写入还在 `-wal` 里，裸复制得到的库是不一致的。
- GraalVM 适配清单（必须进 `NativeRuntimeIT`）：
  1. 启动时把 `org/sqlite/native/{Windows|x86_64,Linux|x86_64}` 的库释放到 `<work-dir>/native/`，设置 `-Dorg.sqlite.lib.path`；
  2. `reachability-metadata.json` 注册 `org.sqlite.JDBC` 反射与上述资源；
  3. 安装包把对应平台的 `.dll` / `.so` 作为外部文件随包发布（`scripts/package-windows.ps1` / `package-linux.ps1` 都要改）；
  4. 原生集成测试断言：`SELECT sqlite_version()`、双库建表、一次执行台账写入；
  5. `native-nightly.yml` 补 Linux 矩阵，并**去掉 `continue-on-error`**——否则闸门形同虚设。
- **兜底**：若 M0 尖峰或夜间任务连续失败且短期无法修复，integrated 形态降级为「瘦 JRE + jar」（放弃单文件 exe），并在发版说明中声明；server 形态（jar）不受影响。**兜底不是现成方案**：打包脚本、安装器目录结构、启动器、发布说明都要另做，需单独排期，不能当成「失败了就自动切过去」。

### 4. 迁移是一次性、幂等、可回滚的

`--migrate-config`（默认在检测到 `config.db` 不存在且 work-dir 有 `*.json` 时自动执行）：导入 `*.json` → `templates/*.json` → 内置模板种子 → classpath 默认配置 → `--config` 外部文件。导入全程是**复制语义**；自检全部通过后才把原文件**移动到 `legacy/`**（不删除），杜绝「迁移半途失败、JSON 已被搬走」的空窗。提供 `--export-config` 导回 JSON；迁移以 `content_sha256` 判重，中断可续。

身份映射是迁移的一部分，不能事后补：

- `sessionId` 现在**就是配置文件名 stem**（`DeviceTemplates.deriveId()` 去 `.json`，`ConfigStore` 反过来把 `session.id` 与 `equipment.name` 强制改写成 stem）。`device.id` 换成 ULID 后，stem 写进 `device.legacy_id` 并建唯一索引；v1 的 `/sessions/{id}`、前端深链 `#/device/{id}`、CLI 脚本、`--config` 全部先按 `legacy_id` 解析。
- `--export-config` 导出的文件名用 `legacy_id`，保证导回后与 `legacy/` 里的原文同名同内容。
- 主机 id 现在是进程内计数器 `"host-" + seq`，重启即变，**没有历史数据可迁**；迁移只负责给新建主机分配持久 ULID（work-dir 里有历史主机导出文件时按 `name + target_host + target_port` 去重导入）。
- 迁移自检（在移动任何文件之前执行）：设备数、模板数、每份正文的 SHA256 与源文件逐一比对、`legacy_id` 无重复、`name` 在未删除行内唯一；任一项不过则整体回滚——DB 回滚、原文件原地不动，work-dir 仍可用 2.x 方式启动，下次启动重新迁移。

### 5. S2F15 设备常量：语义不变，但不产生「配置待生效」

`HostCommandHandler` 的「先落盘再改内存，写盘失败整批不生效、回 EAC=2」保持不变，只把「盘」从 `ConfigStore.writeConstants(file, ...)` 换成 `ConfigRepository.updateConstants(deviceId, ...)` 的一次 SQLite 事务，全成或全滚。

这条事务与使用者的 `saveDocument` **刻意不同**：

| 动作 | 改正文 | 升 revision | 写版本快照 | 重投影 | 追平运行版本 | 产生漂移 |
|---|---|---|---|---|---|---|
| `saveDocument`（使用者保存） | 是 | 是 | 是 | 是 | 否 | **是**（等重载） |
| `updateConstants`（S2F15） | 是 | 是 | **否** | 是 | **是，提交后立即追平** | **否** |

理由：S2F15 是**对端主机改设备运行时状态**，改完立刻生效，不存在「等使用者重载」这一步。若它也留下漂移，界面会永久挂着「配置待生效」横幅，而唯一的消除手段是重载——重载会清掉本次运行的临时编辑与钉值，等于用破坏运行态来消除一个假告警。

因此 `updateConstants` 在 config.db 事务（改正文 + 升 revision + 重投影，不写快照）提交后，**立即**在 runtime.db 里把 `device_link.config_revision` 追平到新值——两库不做跨库事务（见 §1），两步之间崩溃无害：启动对账一律把设备拉回 `STOPPED`，重连按最新正文重建运行态，天然无漂移。快照不写，避免主机侧高频写常量把版本历史刷爆（版本历史只记录使用者的编辑）。ECV 写入**不会**因为使用者正在编辑同一份配置而失败：两者都走单写者串行化，`If-Revision` 只约束使用者的保存路径。

## 备选方案与否决理由

| 方案 | 否决理由 |
|---|---|
| 继续用 JSON 文件，只加索引文件 | 仍是双份真相；版本历史、事务、并发读都要自己实现，复杂度高于引入 SQLite |
| H2 / 纯 Java 嵌入式库 | 不满足「SQLite 数据库」的字面要求，`*.db` 无法与现场工具互通；ADR 0015 也认为「把简单问题复杂化」 |
| 把日志/审计一起搬进 SQLite | 日志是追加轮转形态，文件实现够用；搬了只会放大 JNI 与 WAL 压力，且要重写 ADR 0009/0012 |
| 直接放弃原生镜像 | 丢掉 integrated 形态「无需 JVM」的核心卖点，只在兜底方案里接受 |

## 后果

**正面**

- 设备/主机成为持久实体，「关闭/断开」可以只作用于连接（需求 1/2 的前提）。
- 配置有版本、有差异、有回滚；界面据此显示「版本 v7 · 已保存」与「配置待生效」横幅（需求 4 的后端支撑）。界面不出现 revision / 库名等实现词。
- 运行态与配置态物理隔离，重载语义清晰；执行台账让「计划发了什么、SVID 变成多少」可查（需求 5）。

**负面 / 成本**

- 引入本仓库第一个 JNI 依赖，而原生链路目前是空白（无元数据、无 Linux 夜间矩阵、`NativeRuntimeIT` 无数据库断言）。**M0 尖峰是硬闸门**，打包与夜间任务要跟着改；这是本次改造最大的技术风险。
- 兜底方案「瘦 JRE + jar」需要另做打包与安装器，不是失败即自动生效的退路。
- 多一套迁移与回滚链路，必须配套 `--export-config`、`legacy/` 归档与 `legacy_id` 别名解析。
- `ConfigStore` / `TemplateStore` / `ConfigHandler` / `api/configs.ts` 的乐观锁契约要从 mtime 改成 revision，前端与 OpenAPI 同步改；v1 的 `If-Match` 需要一层映射。
- 跨库不做事务，靠启动对账与幂等键收敛，属于显式接受的最终一致。

## 验收

- **M0 闸门**：原生 exe 内 `SELECT sqlite_version()` 成功、双库建表、一次执行台账写入；`native-nightly` 双平台矩阵去掉 `continue-on-error` 后全绿。不过则本 ADR 退回评审。
- `scripts/check-all.ps1` 全绿（后端 Linux/Windows 双平台、tsc、Vitest、Playwright、openapi 重复键、BOM、界面禁用词）。
- `-Pnative verify` 的 `NativeRuntimeIT` 在原生进程内完成双库读写。
- 迁移演练：拿一份 2.0.1 的 work-dir，迁移后设备数/正文 SHA256 逐一比对；`--export-config` 导回的文件与 `legacy/` 原文同名同内容；旧深链 `#/device/{stem}` 仍能打开对应设备。
- 幂等演练：迁移跑两次，设备与模板不重复；`saveDocument` 用旧 revision 并发写，只成功一个，另一个回 409。
- **S2F15 演练**：主机写一批 ECV 后，界面**不出现**「配置待生效」横幅，版本历史**不新增**快照，`device_link.config_revision` 与 `config_document.revision` 相等；写盘失败时整批不生效并回 EAC=2。
- 备份演练：WAL 有未 checkpoint 写入时执行 `VACUUM INTO`，备份库能独立打开且行数与主库一致。
