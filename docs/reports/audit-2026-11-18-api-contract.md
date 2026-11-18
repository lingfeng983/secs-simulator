# API 契约一致性审计（2026-11-18）

> 范围：v1/v2 会话与设备查询端点的响应形状、SSE 通知帧的契约、`NoticeEvent` 事件类型注释、openapi.yaml 声明与后端实现的一致性。结论基于逐文件阅读与 grep 复核，代码位置均给出（master @ 0cb0e9b）。

## 总体结论

功能都通，但契约层有四类漂移：**①同一命名空间四种响应形状；②SSE 帧身份字段复用；③注释与实现脱节；④openapi 残留已删端点的死声明**。修法收敛为一句话：v2 补齐缺口并统一 envelope，v1 挂 Deprecation 不原地震，注释与 openapi 随实现同步。

---

## 一、问题清单

### P1-1 同一命名空间四种响应形状（v1 会话查询族）

`GET /api/v1/sessions/{id}/X` 下的四个查询端点，HTTP 层（`SessionHandler.handleSessionQuery` :395-401）只透传，形状由 runtime 层各自的序列化方法决定：

| 端点 | 返回形状 | 出处 |
|---|---|---|
| `/alarms` | 裸数组 `[{alid,text,category,atMillis}]` | `SessionJsonViews.alarmsJson` :146 |
| `/terminal` | 裸数组 `[{tid,text,atMillis}]` | `SessionTailView.json` :39 |
| `/events` | `{"events":[…]}` | `SessionJsonViews.eventsJson` :124 |
| `/catalog` | 对象 `{ceids,reports,svids,hostReports,hostLinks,hostAllDisabled}` | `SessionJsonViews.catalogJson` :240-246 |

openapi.yaml 已如实声明这三种形状（alarms/terminal = array，events = `{events}`，catalog = Catalog 对象）——分歧被"合法化"进了契约文档，不是声明遗漏。

**前端被迫三处防御**（`ui/src/api/sessions.ts`）：

- `activeAlarms` :153-160 同时兼容裸数组与 `{alarms:[]}` 两种形状；
- `catalog` :161-175 逐字段兜底；
- `events` :196-201 `body.events ?? []`。

**方案**：不原地震 v1——裸数组是既有契约，直接改会砸到夹具/脚本。统一形状放到 v2 新端点做（见 P1-2），v1 的 `/alarms`、`/terminal` 挂 `Deprecation: true` + `Sunset` 头（`closeSession` 已有先例，`SessionHandler.DEPRECATION_SUNSET` :34 可复用），3.x 周期后移除。

### P1-2 `/alarms` 只有 v1 无 v2

v2 设备运行时资源清单是 `executions/plans/cycle/catalog/svids/message/alarm/ceid`（`DeviceRuntimeHandler.dispatchDeviceRuntimeV2` :151-167），其中 `alarm` 只是 `POST|DELETE /runtime/alarm/{alid}` 触发/清除，**没有活动报警的查询端点**。活动报警查询仍挂在 v1 会话命名空间，前端注释早已预言这笔债（`sessions.ts:50-58`）：

> 后端目前挂在 v1 会话命名空间下；后续批次会迁到 `/api/v2/devices/{id}/runtime/alarms`，届时只改这一行。

**方案**（与 P1-1 合并一批做）：

1. 新增 `GET /api/v2/devices/{id}/runtime/alarms`，回 `{"alarms":[…]}` ——与 `/events` 的 envelope 风格对齐；
2. openapi 补声明（tags: devices）；
3. 前端 `activeAlarmsUrl` 切到 v2，删掉双形状兜底（sessions.ts:154-159 的"夹具/老后端"分支）；
4. v1 `/alarms`、`/terminal` 挂 Deprecation/Sunset。

### P2-1 SSE 帧 `sessionId` 字段复用承载四种身份

`StreamHandler.broadcastNotice`（:272-278）把 `event.sessionId()` 无条件写进通知帧的 `sessionId` 字段，但按事件类型它装的东西不同：

| 事件类型 | `sessionId` 字段实际装 | 发布点 |
|---|---|---|
| `s6f11` / `alarm` / `session` | 会话 id（ses_ ULID） | `SessionNotifier` :82 |
| `plan-fired` | 设备 id | `PlanFiredThrottle` :137 |
| `device-link` | 会话 id（payload 另有 deviceId） | `Bootstrap` :206 |
| `host-link` | **hostId**（payload 里重复一份 hostId） | `HostSimRegistry` :119-122 |
| `config-changed` | **配置名**（payload 里重复一份 name） | `RuntimeContext.announceConfig` :716-725 |

**消费端证据**：

- `useEventStream.ts:184` 把每帧的 `sessionId` 统一解构进 `NoticeWire.sessionId`——类型上承诺它是"会话 id"；
- `App.tsx:216-219` 处理 `host-link` 时用的是 payload 的 `data.hostId` 而不是 `notice.sessionId`——**消费者已经不信任这个字段**，说明它语义已死；
- 唯一信任方是 `alarmRows.ts:50` 的 `notice.sessionId !== sessionId` 过滤，正确纯属隐式约定（alarm 发布方恰好装了会话 id）；
- host-link / config-changed 的通知带着假 sessionId 进了 `App.tsx` 的 `setLiveEvents` 环形缓冲。

**风险**：`NoticeEvent` 的 record 组件名就叫 `sessionId`，发布方（如 `HostSimRegistry` 把 hostId 塞进"会话标识"参数位）被字段名误导。目前配置名与会话 id 不同空间（配置名 stem vs ses_ ULID）不会相撞，但"按 sessionId 过滤通知"是很自然的写法——哪天对 config-changed 也这么写，同名即误命中。

**方案**：SSE 通知帧按类型给显式身份字段（`deviceId` / `hostId` / `configName`），`sessionId` 只留给会话域事件（s6f11 / alarm / session / device-link）。后端可顺手把 `NoticeEvent` 组件改名 `sourceId` 从源头止住误用；前端 `NoticeWire` 类型与消费方同步。这项契约面最大（7 个事件类型 + 前端全部消费方），单独一批做。

### P3-1 `CONFIG_CHANGED` 注释漏 `save-section`（一行修）

`NoticeEvent.java:37-38` 写的是 `action=save|create|delete`，但 `RuntimeContext.java:698` 在分段保存（2026-11-18 §4.4）后发的是 `"save-section"`。实际取值全集：**`save | save-section | create | delete`**（调用点 :689/:706、:698、:735/:742、:750）。前端 `useEventStream.ts:13` 的同类注释也没提 save-section。

功能没坏：`App.tsx` 的处理分支不区分 action（仅 delete 特判）、前端也没有 action 字面量类型。纯文档漂移。

**方案**：两处注释补 `save-section`，零风险立即做。

### P1-3（附带发现）openapi 残留 B2 已删端点的死声明

B2 已把 v1 会话实体编辑端点全部移除（后端 `SessionHandler` :467-469 对非 ceid/alarm 的 POST 子资源统一回 404，注释明说"svids、reports、ceids、events、plans…已删"），但 openapi.yaml 还声明着这批端点：

| 死声明 | 行号 |
|---|---|
| `POST /api/v1/sessions/{id}/events`（新增计划） | :296 |
| `POST /api/v1/sessions/{id}/events/{planId}`（启用/禁用） | :336 |
| `POST /api/v1/sessions/{id}/ceids`、`PUT …/ceids/{ceid}` | :550/:570 |
| `POST /api/v1/sessions/{id}/reports`、`…/reports/{rptid}` | :601/:620 |
| `POST /api/v1/sessions/{id}/svids`、`PUT …/svids/{svid}`、`PUT …/svids/{svid}/value` | :651/:670/:701 |

注意：`GET /sessions/{id}/events` 还活着（编排表查询），只删 POST 部分；替代端点是 `POST/PUT /api/v2/devices/{id}/runtime/plans…`。

**方案**：从 openapi 删掉这批 path 条目；这是纯文档清理，不影响运行。

---

## 二、修复批次建议

| 批次 | 内容 | 风险 |
|---|---|---|
| 一 | 注释补 `save-section` ×2（P3-1） | 零风险，可立即做 |
| 二 | v2 alarms 查询端点 + openapi 同步 + 前端切换 + v1 alarms/terminal 挂 Deprecation（P1-2、P1-1） | 中：新端点是加法，v1 只加响应头 |
| 三 | openapi 删 7 组死声明（P1-3） | 低：纯文档 |
| 四 | SSE 通知帧身份字段重构（P2-1）：后端 NoticeEvent/StreamHandler + 前端 useEventStream/全部消费方 | 高：契约面最大，涉及 7 个事件类型，单独一批并过真机 e2e |

批次一、三可以合并进任何一个 PR 顺手做；批次二是前端注释里"后续批次"的正主；批次四建议等批次二落地后再排（两者都会动 `sessions.ts`）。
