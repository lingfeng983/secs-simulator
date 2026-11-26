# secs-core 修复日志（fix-log-core）

日期：2026-11-24 ｜ 范围：仅 `secs-simulator-service/secs-core/` ｜ 验证：`mvn -B -pl secs-core test`（JAVA_HOME=E:\Java\graalvm-jdk-25.0.4+7.1）

## 一、上一轮已落盘项（主 Agent 已验证，本轮未重做）

| 项 | 内容 | 状态 |
|---|---|---|
| C1 | `BoundedValueSource.Bounds.of`：F4 单列值域 `±Float.MAX_VALUE`（不再与 F8 共用 `Double.MAX_VALUE`），越界在取值处钳制而非推迟到协议编码层 | ✅ 已落盘，本轮补回归单测 |
| C2 | `EquipmentCompiler`：`DUPLICATE_UNIT_NAME` 由 WARNING 升级为 ERROR（阻断启动）；`reportByQualified` / `unitByCeid` 改 `putIfAbsent`（限定报告引用 first-wins，后编译单元不再静默覆盖） | ✅ 已落盘，本轮补回归单测 |
| C4 | 按主 Agent 通报上一轮已落盘。本轮未复核其原始编号定义（评审 final.md 仅列 C1/C2；dim06 的 C4 属 API 层不在本模块范围）。代码证据：`BoundedValueSource.Bounds.of` 中 U8/I8 上界采用 Long 范围 + `Math.nextDown((double) Long.MAX_VALUE)`（避开 2^63 舍入），既有测试 `u8AndI8ClampToLongRange` 覆盖，疑似即该项 | ✅ 按通报已落盘（编号对应有证据缺口，见上） |

## 二、本轮完成项

### P2 周期相位（右开区间 [0, duration) 契约）— ✅ 完成
- `cycle/CompiledCycleClock.java` `phaseAt`：改为 `index = indexAt(tMs); phase = tMs - startOf(index); return Math.max(0L, Math.min(phase, durationOf(index) - 1L));` —— 负时刻钳 0，t ≥ endMs 钳到末片右开端点 duration−1，附注释。
- `cycle/LifecycleCycleClock.java` `finish`：冻结相位 `durationOf(count-1)` → `durationOf(count-1) - 1L`，注释说明右开端点。
- `cycle/LifecycleCycleClockTest.java` 同步断言：`finishedFreezesAtLastWaferEnd` 10_000L→9_999L；`passthroughRunsFromClockZeroAndFinishesAfter24h` 86_400_000L→86_399_999L。

### P3 ExprSource 防御性拷贝 — ✅ 完成
- `value/DataSource.java` `ExprSource` 增加 compact constructor：`vars = vars == null ? Map.of() : Map.copyOf(vars);`（record 结构确认：`ExprSource(Map<String,Curve> vars, Expression expr, Clock clock)`；调用方 SourceEvaluator 只读 `vars()`，无兼容风险）。

### P2 Context 数值 source 静默空串 — ✅ 按约定不改行为
- `value/SourceEvaluator.java` `text()` default 分支加注释：数值型来源（CurveSource/ExprSource/CycleNumber）落此返回空串，Context 误配会静默编码成空 ASCII；属 config 层校验职责（跨模块），core 保持现状。
- **留待 config 层**：增加「Context 项禁止数值型 source」校验；届时 `ContextTableTest.numericSourceSilentlyYieldsEmptyTextForNow` 应改为断言拒绝。

### 补单测（评审确认的覆盖洼地）— ✅ 完成，共新增 22 个测试
| 文件 | 新增 | 内容 |
|---|---|---|
| `value/BoundedValueSourceTest.java`（改） | +1（`f4ClampsToFloatRange`） | F4 边界：1e100→Float.MAX_VALUE、−1e100→−max、NaN→0、±Inf→±max、恰在上界放行不计数、clampedCount=5 |
| `compile/EquipmentCompilerTest.java`（改） | +2 | `duplicateUnitNameIsError`（DUPLICATE_UNIT_NAME 必须在 errors() 且编译失败）；`qualifiedReportReferenceIsFirstWinsOnDuplicateUnitName`（重名单元下限定引用 CH1.STATUS 解析到先编译单元报告 3001） |
| `cycle/CompiledCycleClockTest.java`（新） | 6 | t=0、片切换瞬间归零、t≥endMs 钳制、负时刻钳 0、单片 passthrough 24h 边界、带抖动多片相位恒 < duration |
| `value/SourceEvaluatorTest.java`（新） | 6 | Const/Curve+transform 确定性求值；ExprSource 局部变量/未定义变量抛 ExpressionException/vars 可空；NaN/Inf 按现状穿透（分层契约：归一在 BoundedValueSource）；文本↔数值语义边界（含 P2 空串现状锁定）；CycleNumber 步进与超界停末片；localTime 三种时钟映射 |
| `value/DataSourceValueSourceTest.java`（新） | 3 | 已知/未知编号（empty）、同 (svid,t) 重复读取一致 + 同种子跨实例可复现、CycleNumber 随时钟走且超界钳制 |
| `context/ContextTableTest.java`（新） | 4 | 文本随片走（LOT{02}）、快照保持定义顺序、空表/重名/空白 id 拒绝、数值 source 空串现状锁定 |

全部用确定性构造（`CycleClock.of` / `ConstantCurve` / `Rng` 固定种子），无 mock。ManualEngineClock 未用到：上述类的公开 API 均直接收 `tMs`，不经 EngineClock。

## 三、改动文件清单

主代码（本轮 4 个）：
- `secs-core/src/main/java/.../core/cycle/CompiledCycleClock.java`
- `secs-core/src/main/java/.../core/cycle/LifecycleCycleClock.java`
- `secs-core/src/main/java/.../core/value/DataSource.java`
- `secs-core/src/main/java/.../core/value/SourceEvaluator.java`

主代码（上一轮，本轮未动）：`value/BoundedValueSource.java`、`compile/EquipmentCompiler.java`

测试（改 3 + 新 4）：
- 改：`LifecycleCycleClockTest.java`、`BoundedValueSourceTest.java`、`EquipmentCompilerTest.java`
- 新：`cycle/CompiledCycleClockTest.java`、`value/SourceEvaluatorTest.java`、`value/DataSourceValueSourceTest.java`、`context/ContextTableTest.java`

## 四、验证结果（实际运行）

命令：`cd secs-simulator-service && JAVA_HOME='E:\Java\graalvm-jdk-25.0.4+7.1' mvn -B -pl secs-core test`

```
Tests run: 111, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   (Total time: 4.114 s, Finished at: 2026-11-24T22:33:25+08:00)
```

- 一次通过，无失败重跑。新增/改动测试类实测：EquipmentCompilerTest 17（+2）、BoundedValueSourceTest 4（+1）、CompiledCycleClockTest 6、SourceEvaluatorTest 6、DataSourceValueSourceTest 3、ContextTableTest 4、LifecycleCycleClockTest 5（断言更新）。
- 零告警门禁：父 pom 配置 `-Xlint:all,-missing-explicit-ctor` + `-Werror`（secs-simulator-service/pom.xml:127-128），主/测试编译均在该门禁下通过，即零告警。

## 五、遗留与缺口

1. **C4 编号对应**：评审 final.md 仅确认 C1/C2 为 core 项；C4 按主 Agent 通报视为已落盘，其与代码改动的精确对应本轮未复核（疑似 U8/I8 Long 范围钳制，代码与既有测试均在）。
2. **Context 数值 source 校验**：留待 config 层（跨模块，超出本轮 write_scope），core 侧已加注释 + 现状锁定测试。
3. 本轮未跑全仓 `mvn test`（仅 secs-core 模块，按任务要求）；下游模块若有依赖旧相位端点值（duration 而非 duration−1）的断言，需在各自模块验证时同步——本轮 write_scope 不含其他模块，未检查。