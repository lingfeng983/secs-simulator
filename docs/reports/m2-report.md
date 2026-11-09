# M2 验收报告 · 值引擎 + 真 S1F3 链路

## 交付

| 层 | 内容 |
|---|---|
| `secs-core/curve` | `Curve` 密封接口 + 7 种曲线：固定、斜坡（可循环）、区间随机、随机游走（漂移+分形噪声）、正弦、阶跃、关键帧折线 |
| `secs-core/curve` | `Rng`：确定性哈希噪声场（`uniform01` / `smooth01` / `fbm` / `derive`）、`BoundPolicy`（CLAMP / REFLECT / WRAP） |
| `secs-core/value` | `ValueStore`：SVID → 曲线绑定，按 SVID 派生独立噪声源，`valueAt` / `snapshot` |
| `secs-app/session` | `SessionSpec` / `SessionState` / `SessionRuntime` 端口 / `SessionManager` / `DefaultCurves` |
| `secs-runtime/session` | `HsmsSessionRuntime`：打开 HSMS-SS 端口，应答 S1F1 与 S1F3→S1F4；`Secs2Mapper`（类型映射）、`Secs2Read`（取值适配）、`DemoEquipment`（两腔体） |
| CLI | `--demo-session`：直接在 `--hsms-port` 上开一个两腔体演示会话 |

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **57 个**（core 42 + runtime 12 + cli 3），全绿 |
| 曲线黄金指纹 | `3ba1c704`（60 s × 4 条曲线的 CRC32） |
| 原生镜像 | `secs-sim.exe` **12.9 MB**，构建 65 s |
| 原生端到端 | 2 个用例：进程握手/鉴权/SSE/S1F1-S1F2/停机，以及 **`--demo-session` 下的 S1F3→S1F4**，合计 1.4 s |

## 真链路证据（`SessionS1F3Test`）

用**手动时钟**把时间钉死在 0，因此可以断言"S1F4 里的数就是引擎在 t=0 算出的那个数"，而不是"某个合理区间里的数"：

| 用例 | 断言 |
|---|---|
| S1F1 | 回 S1F2 |
| S1F3 [1001, 1090, 1101] | 个数为 3；CH1 温度 == 引擎 t=0 值；UNIT_ID == 1；CH2 温度 == 引擎 t=0 值 |
| S1F3 [9999]（未知 SVID） | 回 0；随后 S1F1 仍然可用（不断链） |
| 时间前进 120 s 后再问 1001 | 值发生变化，且仍落在声明值域内 |

## 踩坑记录

**`Secs2.getInt(int... indices)` 必须至少给一个下标。**

它写成变长参数，但实现是 `LinkedList.removeLast()`，无参调用会抛
`NoSuchElementException`——报错信息看起来像"消息是空的"，实际是"少写了个 0"。
表现是 S1F3 处理线程静默异常、客户端 T3 超时 45 秒，非常难猜。
已收口到 `Secs2Read.asInt/asDouble`。

## 与 M1 的架构关系

会话实现被放在 `secs-runtime`，应用层只认 `SessionRuntime` 端口，
HTTP 层完全不知道 HSMS 的存在。这样后续加 SECS-I、加主动模式、
甚至把会话换到别的进程，都不需要动应用层与 UI。

## 下一步（M3）

- 模板 DAG：从"每个 SVID 一条曲线"升级为"一组 SVID 由一个模板统一生产"
- 5 级参数覆盖（默认 → 单元模板 → 单元 → profile → 运行时手动）
- 表达式 DSL（受限、纯函数）与派生/聚合
- 预览批算（沙盒跑完整条时间线）
