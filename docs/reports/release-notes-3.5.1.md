# 3.5.1 发布说明

发布日期：2026-11-26
类型：缺陷修复版（并发与一致性加固；含少量行为变化，升级前请读第五节）

3.5.0 之后做了一轮全栈评审整改。这一版不改界面长相，重点是修那些平时看不见、
一旦并发或异常就会暴露的问题：压测取消要等一分钟、S2F15 写常量覆盖别人的编辑、
控制帧撞号被当成数据应答、SSE 畸形载荷拼出非法 JSON、归档页面把后台词直接说给用户听。
同时补上了两条此前一直"假绿"的门禁：ArchUnit 分层规则在 `verify` 阶段空转，
原生 S1F3 用例因路径错误一直跳过。

## 一、后端：并发与一致性

- **压测取消从最多 60 秒收敛到亚秒**：`BurstRunner` 每 250 ms 检查一次取消标志，
  不再睡完整个分片才响应。
- **控制状态原子化**：`ControlStateHolder.change` 加 `synchronized`，
  消掉 check-then-set 窗口；GEM 动态报告/链接/使能项重定义同样按集合加锁，
  不再留下两条同 ID 定义。
- **HSMS 控制帧配对**：`Pack` 记录期望的 SType，`DESELECT_REQ` 纳入 T6 等待；
  SystemBytes 撞号时数据 secondary 不再被误当控制应答吞掉。
- **存储写入安全**：S2F15 写常量带 `expectedRevision` 乐观锁，冲突返回失败而不是覆盖
  用户在界面上的编辑；`Db.closed` 改 `volatile`、关闭前先取两把写锁等在途事务；
  七表孤儿清理合成一个事务；执行台账重建行默认不启用，设备销毁后计划不再"复活"。
- **会话与主机**：重载失败用 `lastGood()` 回滚旧会话；`applyHot` 纳入 `pumpLock`
  保证多步替换的原子可见；主机实例上限 64，超限先拒绝，不留孤儿行。

## 二、编译与值域契约

| 项 | 以前 | 现在 |
|---|---|---|
| 单元重名 | WARNING，配置仍能启动 | **ERROR**，启动失败 |
| 同名报告/CEID 归属 | 取决于声明顺序 | first-wins，结果确定 |
| 工艺周期相位 | 可能越界或无界 | 统一 `[0, duration)`，冻结在 `duration - 1` |
| F4 浮点上界 | `Double.MAX_VALUE`，超范围值漏到协议编码层 | `Float.MAX_VALUE` 钳位 |
| `ExprSource.vars` | 直接持有外部 map | 防御性拷贝 |

重名从警告升为错误是这版最需要留意的行为变化：以前能起来的配置现在会明确拒绝，
避免"同一地址指向谁"随声明顺序漂移。

## 三、HTTP、SSE 与 API 契约

- 访问日志状态码统一走 `HttpSupport`，修掉健康检查、静态资源、503/500 状态码恒为 0
  的问题，排障时终于能相信日志里的那一位。
- SSE 拆壳前先用 `JsonHub` 校验 payload 确实是个 JSON 对象；畸形载荷走通用包装分支，
  不会再拼出半截 JSON 帧。
- `check-openapi.py` 增加 `$ref` 转义与外部引用拒绝、path 清单快照比对、注册前缀覆盖检查；
  `openapi.yaml` 补上 v2 分段保存/批量新建/压测/删除端点，v1 的报警与终端查询挂上废弃说明。

## 四、前端

- 删除会话维度采集器死代码（`collectorState` / `setCollector` / `collectorCommand`），
  只留实例维度的 v2 端点；不再维护两套没人用的封装。
- 8 处轮询统一切到 `useVisibilityInterval`：页面不可见时不发请求，回到页面立即补一次；
  压测轮询支持 `AbortSignal`，卸载或重发时真正停下来。
- 批量同步配置先读 `modifiedMs` 走 If-Match，409 计入失败并逐台回报原因；
  "重载目标"按配置字段是否存在判断，避免对没有该字段的旧会话误伤全量。
- 新建区域从 `window.prompt` 改为受控弹窗（焦点圈定、Esc 关闭、主题一致）；
  重启提示改用用户能看懂的说法，去掉后台实现词。
- 消息监控抽屉的峰值计算由 `Math.max(...map)` 改成 `reduce + useMemo`，
  5000 条报文不再有展开爆栈风险，并补了"载入更早"。

## 五、升级须知

| 变化 | 影响 | 建议 |
|---|---|---|
| 单元重名现在是编译错误 | 原先能启动的重名配置会被拒绝 | 启动前检查单元名唯一 |
| 相位冻结在 `duration - 1` | 曲线预览最后一个采样点数值可能变化 | 按新口径核对预期值 |
| S2F15 冲突返回失败 | 并发写常量时一方会收到冲突响应 | 客户端重试或提示用户刷新后再写 |
| v1 `DELETE /sessions/{id}` 只断链不删实体 | 旧脚本若依赖"删除后消失"会看到实体仍在 | 改用 v2 `DELETE /devices/{id}` |
| 主机实例上限 64 | 超限创建被拒绝 | 先清理不再使用的实例 |
| `live-backend.ps1` 改从 `JAVA_HOME` 找 java | 原先硬编码的本机 JDK 路径不再生效 | 设置 `JAVA_HOME` 指向 JDK 25+ |

**配置文档格式与数据库 schema 不变**，无需迁移；不需要手工清理 localStorage。

## 六、构建与测试门禁

- CI 增加 Rust `cargo check --locked`（桌面壳）与界面文案门禁；
  文案门禁本次新抓出一处实现词，已改为用户视角表述。
- `scripts/package.ps1` 用 XML 解析顶层 `project/version`，把两个 `package.json` 也纳入
  一致性校验；`-Skip` 开关下产物过旧只告警不阻断。
- 分层门禁修正：`mvn test` 的依赖是 `target/classes` 目录，`mvn verify` 是各模块 jar；
  去掉 `DO_NOT_INCLUDE_JARS` 后 ArchUnit 在两种阶段都能看到完整类集，哨兵不再误报。
- 原生 S1F3 用例修正：演示配置路径少退一级、配置名断言更新为 `demo-etch.json`；
  这条用例此前一直被 `assumeTrue` 跳过，现在是真跑。

## 七、门禁结果

| 检查 | 结果 |
|---|---|
| `npx tsc --noEmit` | 通过 |
| 界面文案门禁 | 通过：144 个文件 / 7250 段文案 |
| Vitest | 35 个文件 / 258 项通过 |
| Playwright（Mock E2E） | 75 项通过 |
| `mvn -B test`（后端全模块） | BUILD SUCCESS，806 项通过 |
| `mvn -B -Pnative verify` | 9 个模块 BUILD SUCCESS，`NativeRuntimeIT` 3/3 无跳过 |
