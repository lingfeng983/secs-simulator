# SECS Simulator 2.0.1 发布说明

发布日期：2026-11-10

## 下载

| 平台 | 文件 | 适用场景 |
|---|---|---|
| Windows | `SECS Simulator_2.0.1_x64-setup.exe` | 现场调试：装完即用，内置原生后端（`secs-sim.exe`），不需要 JVM |
| Linux | `secs-sim-server-2.0.1-linux.zip` | 部署在设备所在网段的服务端，由 Windows 端远程连接；需要 JDK 25+ |

## 这版修了什么

2.0.1 是缺陷修复版：HTTP 路径、配置格式（Schema v5/v6）、启动参数与 2.0.0 完全一致，
不需要改配置或脚本。修复集中在“偶发但难排查”的几处行为。

### HSMS 连接与选中状态

- 在非应答期收到应答类控制帧（`Select.rsp` / `Deselect.rsp` / `Linktest.rsp`，例如迟到的应答）
  时，按 E37 回一条 `Reject.req(TRANSACTION_NOT_OPEN)`，不再**落空到 `Deselect.req` 分支
  把已选中的连接拆掉**——这是此前“连接莫名其妙断开、只能靠重连恢复”的根因之一。
- `Reject.req` 现在只吸收、不回应（它是终点消息，再回一条会因 SystemBytes 原样回带而对端互回，形成循环）。
- 主动端收到 `Deselect.req` 后结束本连接并按 T5 重连重选；被动端保留 TCP 连接，两侧差异是有意为之。

### 事件编排与 S6F11

- S6F11 通知里的 `planId` 改为**真正触发它的那条计划**（不再按 CEID `findFirst()` 猜）；
  同一 CEID 挂多条计划时，界面不会再高亮错的那条。
- 事件效果只施加触发计划的效果；手动“上报”没有计划上下文，仍按该 CEID 全部启用计划取并集。
- `planId` 用 JSON 写出器转义：含引号、反斜杠、换行的计划名不再产出非法 SSE 帧。
- 效果计算改用**实时编排表**（`EventScheduler::previewSchedule`）：运行期新增/删除/禁用计划
  立即生效，不再出现“刚加的计划效果不施加、已删的计划还在改值”。

### Trace（S2F23 / S6F1）

- 单条 Trace 的样本总数上限 1,000,000；超过时回 `TIAACK=3`（非法周期），不再让采样线程长期持锁。
- 单次 poll 补点上限 10,000：笔记本休眠或墙钟跳变后快进到当前时刻，不构造海量帧。
- **快进越过末尾时先补发已算出的末帧再清空**：`totsmp` 较大且 `repgsz` 不是 1 时，
  主机不再永远等不到最后一条 S6F1。
- 快进目标钳到 `totsmp + 1`，不越界。

### 曲线预览与 HTTP 边界

- 实时预览与草稿预览统一 24 小时窗口上限，超出回 `400 range-too-large`；
  `to < from` 的倒挂区间同样按 400 拒绝（不再溢出成负窗口）。
- `EventSimulator` 增加 100 万 tick 的第二道防线：`maxEvents` 只限制“触发次数”，
  条件恒假的计划不会让 `fires()` 增长，此前可以空转 CPU。
- SSE 通知的载荷判定从“长度 ≤ 2”改为“空串或 `{}`”：`[]` 这类合法数组不再被当成空载荷丢掉，
  非对象载荷整体放进 `payload` 字段，不会拼出非法帧。
- 草稿预览的异常映射收窄：`IllegalArgumentException` / `ConfigException` /
  `GraphException` / `ExpressionException` 都按“草稿非法”回 422；
  只有临时文件读写失败（`IOException`）才回 500。模板里写错一个 `p.x` 不再冒充服务端故障。

### 值与报文渲染

- U8 / I8 的取值范围按 `long` 收紧，并用 `Math.nextDown` 避开
  `(double) Long.MAX_VALUE` 舍入到 2^63 后仍超出 `long` 的问题。
- SML 渲染转义 ASCII 文本里的反斜杠与引号：含引号/反斜杠的报文经过面板回填再发送时，
  不会被改写，也不会解析失败。

### 内置演示与配置播种

- `--demo-session` 先校验端口与 deviceId 再播种模板，越界时直接返回码 3，
  不再往工作目录里落下两份用不了的模板。
- 端口 / deviceId 通过启动参数覆盖（`SessionOverrides`），不改写模板文件文本：
  模板排版变化不会让覆盖静默失效。
- 播种是幂等的：已有同名文件（包括用户改过的）一律不动，删除文件后重启即恢复默认。

## 安装与启动

### Windows

1. 双击 `SECS Simulator_2.0.1_x64-setup.exe` 安装并启动。
2. 默认「本机运行」：桌面前端连内置的 `secs-sim.exe`，不依赖本机 JDK。
3. 需要连远程服务端时，点右上角「连接」填地址；留空并保存即切回本机，选择会记住。

### Linux 服务端

```bash
unzip secs-sim-server-2.0.1-linux.zip
cd secs-sim-server-2.0.1-linux
./start-server.sh                 # API 8080（绑 0.0.0.0）、HSMS 5000、数据目录 ./work
./start-server.sh -b 127.0.0.1    # 只绑回环
```

参数：`-p` API 端口、`-b` 绑定地址、`-h` HSMS 端口、`-w` 数据目录。
目标机必须装 JDK 25+（jar 用 class 文件版本 69 编译，JDK 21 会报
`UnsupportedClassVersionError`）。

## 从 2.0.0 升级

- 直接替换可执行文件/安装包即可，`work/*.json` 与前端保存的“连接地址”都可沿用。
- HTTP API 路径与方法未变，`docs/openapi.yaml` 无需调整。
- 若此前为规避“连接莫名断开”而调大过 T5/T6 之类的超时，可以改回默认值。

## 质量与验证

| 检查 | 结果 |
|---|---|
| 后端单测 | 609 个测试通过（7 个含测试模块全部绿色） |
| Windows 包 | 原生 `secs-sim.exe` 启动后 `/api/v1/health` 返回 `version=2.0.1` |
| Linux 包 | 解压后 `start-server.sh` 起 server 模式，`version=2.0.1`（`vm=jvm`），优雅停机正常 |

## 已知边界

与 2.0.0 相同：

- S2F41/F42 远程命令只回 HCACK，不执行任何设备动作。
- Spooling（S2F43–F48）未实现，断线期间的事件不缓存、不补发。
- 未建立通信（ONLINE）前的 S6F11 / S6F1 不主动发送；手动“上报”不受此限。
- 未知 SVID 的 S1F3 / S1F4 恒回 `U4 0`；S1F11 里名字给 `UNKNOWN` 以便区分。
- 报警文本（ALTX）仅支持 US-ASCII，入口即拒。
- 单会话 Trace 默认 1 条、每条 ≤10 SVID，可在 `session.trace` 调大后重建会话；
  单条 Trace 的 `totsmp` 上限 1,000,000。

## 相关文档

- [使用手册](../guides/user-guide.md)
- [配置格式 Schema v5](../guides/schema-v5.md)
- [SVID 值变化机制](../guides/svid-value-model.md)
- [HTTP API 契约](../openapi.yaml)
- [2.0.0 发布说明](release-notes-2.0.0.md)
