# M3 验收报告 · 模板 DAG + 表达式 + 参数分层

## 交付

| 组件 | 内容 |
|---|---|
| `Expression` | 递归下降解析器 + AST 求值；四则运算、一元负号、12 个纯函数；解析期做函数名与参数个数校验 |
| `Template` | 多输出生产单元接口（`outputs` / `inputRefs` / `evaluate`） |
| `SingleCurveTemplate` | 把一条曲线包装成单输出模板，让"单曲线"退化为 DAG 的特例 |
| `CompositeTemplate` | 每个输出一个表达式，可引用参数与其它实例输出（`in.实例.输出`） |
| `TemplateGraph` | 拓扑排序、环检测、单写者校验、`evaluate` / `evaluateRange`（预览批算基座） |
| `ParameterLayers` | 5 级覆盖（模板默认 → 单元模板 → 单元 → profile → 运行时），不可变 |
| `ValueSource` | 值来源抽象：`CurveValueSource`（单曲线）与 `GraphValueSource`（DAG，带单时刻缓存） |
| 会话 | `HsmsSessionRuntime` 支持注入 `ValueSource`，因此 S1F4 可以由 DAG 驱动 |

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **80 个**（core 64 + runtime 12 + cli 4），全绿 |
| 原生镜像 | `secs-sim.exe` **12.9 MB**，原生端到端 2 个用例 0.9 s |

### DAG 驱动 S1F4 的证据（`SessionGraphS1F3Test`）

模板图：`temp`（斜坡 0→100/100s，绑 SVID 1001）→ `sv`（`in.temp.V * 0.9 + 10`，绑 1002）。

| 时刻 | 请求 | 期望 | 结果 |
|---|---|---|---|
| t=0 | S1F3 [1001, 1002] | 0 / 10 | ✅ |
| t=50s | S1F3 [1001, 1002] | 50 / 55 | ✅ |
| t=50s | S1F3 [1090] | 1（常量输出） | ✅ |

也就是说：**主机通过 S1F4 拿到的第二个值是第一个值派生出来的**，
两个 SVID 由同一个模板统一生产，而不是两条各自为政的曲线。

## 结构约束清单（构建期强制）

| 代码 | 含义 |
|---|---|
| `DUPLICATE_INSTANCE` | 实例标识重复 |
| `DUPLICATE_SVID` | 同一 SVID 被多个输出绑定（违反单写者） |
| `UNKNOWN_INSTANCE` | 引用了不存在的实例 |
| `UNKNOWN_OUTPUT` | 引用了该模板不存在的输出 / 绑定了不存在的输出 |
| `BAD_REFERENCE` | 引用格式不是 `实例.输出` |
| `CYCLE` | 模板图有环（会报出涉及哪些实例） |
| `UNKNOWN_PARAM` | 求值时引用了未定义参数 |
| `UNKNOWN_INPUT` | 求值时引用了未产出的输入 |

## 还没做的（M4/M5）

- **预览批算的统计层**：`evaluateRange` 已能出逐点结果，但分桶降采样与极值统计还没做（预览 UI 需要）。
- **归一化/量化/滞后等包装模板**：需要状态的那一类，按 ADR 0003 只能做包装且单调前推。
- **内置模板库**：`thermoLoop` / `gasPanel` / `alarmTriplet` 这些领域模板。
- **S6F11 编排**：CEID → 报告 → 现场值，以及多单元错峰。
- **配置落盘**：目前设备模型与模板图都是代码里构造的，还没接 JSON Schema v4。

## 下一步建议

优先做 **S6F11 编排 + 配置 JSON**：有了这两个，产品才"能被现场配置出来"，
而 M3 的 DAG 也从"能跑"变成"能被人用"。
