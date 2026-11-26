# secs-core 领域层专项评审（维度 03：值引擎 / 周期时钟 / ID 编译 / 曲线纯函数）

- 日期：2026-11-24
- 范围：`secs-simulator-service/secs-core/src/main/java`（只读评审），辅以 app/runtime 侧调用方 grep 证据
- 方法：逐文件阅读核心类 + 全仓引用搜索；未运行构建或测试

## 现状概述

core 领域层已完成 v7 单一值定义模型的落地：`DataSource`（sealed interface，六种来源）+ `SourceEvaluator`（唯一求值函数）+ `DataSourceValueSource`（编号→source 映射）构成值管线；`ContextTable` 承载晶圆属性文本；周期时钟拆成纯函数 `CompiledCycleClock` 与有状态装饰器 `LifecycleCycleClock`；`EquipmentCompiler` 负责槽位相对 ID → 绝对 ID 的编译与一次性报错。曲线族（8 个 sealed 实现）与 `Rng` 均为时间纯函数，符合 ADR-0003 的设计意图（ADR 文档本身本次未读取，见置信度说明）。

v6 遗留 `ValueStore` / `CurveValueSource` **仍存在于 core 且有生产调用方**：app 侧 `ValueSources` 把 `CurveValueSource` 作为"未配置任何 source 时的默认曲线"回退路径（有意保留），但 runtime 侧 `HsmsSessionRuntime`、`HotReloader`、`RuntimeCatalog` 也直接构造它们，双管线并存（详见逐项核查 4）。

主要发现：F4 钳制上界错误使用 `Double.MAX_VALUE`（P1）；单元重名仅 WARNING 而限定报告索引 `reportByQualified.put` last-wins，全局 CEID 限定引用可被静默歧义解析（P1）；RUNNING 态末片之后、`pump()` 触发 FINISHED 之前存在相位越界窗口，且 FINISHED 冻结相位恰等于 duration，均违反 `[0, duration)` 契约（P2）。表达式引擎有深度 64 / 运算符 1024 双闸，除零走 IEEE 语义依赖下游钳制。core 测试对 v7 值模型核心类（SourceEvaluator / DataSource / DataSourceValueSource / ContextTable / CompiledCycleClock）**无直接单测**。

## 逐项核查

### 1. 值引擎 v7 核心类

**1.1 求值入口与优先级** — `core/value/SourceEvaluator.java:33-63`：唯一求值函数，按 sealed switch 穷举六种来源，无叠加层/效果队列：

```java
public static double numeric(DataSource source, long tMs, CycleClock clock, Rng rng) {
    return switch (source) {
        case DataSource.Const constant -> constant.value();
        case DataSource.Text ignored -> 0.0;
        case DataSource.CycleNumber step -> step.valueAt(clock.indexAt(tMs));
        case DataSource.CycleText ignored -> 0.0;
        case DataSource.CurveSource curve -> {
            long local = localTime(curve.clock(), tMs, clock);
            double value = curve.curve().at(local, rng);
```

`CurveSource.transform` 只允许 `V`/`v`/`t`（`SourceEvaluator.java:44-48`，越权变量抛 `ExpressionException`）；`ExprSource` 局部曲线按名派生噪声 `rng.derive("var:" + name)`（第 60 行），未定义变量显式抛错（第 58 行）。求值优先级即"一个数据项一份 source"，不存在跨编号依赖——与 ADR-0020 的收窄方向一致。

**1.2 时钟换算** — `SourceEvaluator.java:101-110`：

```java
return switch (clockKind) {
    case PHASE -> clock.phaseAt(tMs);
    /* ABSOLUTE 走生命周期锚：无装饰器时 = tMs（时钟零点起算，3.1 语义不变）；
       有 LifecycleCycleClock 时 = t - startedAt——ABSOLUTE 曲线随周期重锚，
       重启周期即重放（2026-11-16 设计 D11）。 */
    case ABSOLUTE -> clock.elapsedAt(tMs);
    case CYCLE -> clock.indexAt(tMs);
};
```

**1.3 噪声派生** — `core/value/DataSourceValueSource.java:49-53`：

```java
Rng rng = this.root.derive("svid:" + svid);
if (this.noisePerWafer) {
    rng = rng.derive("cycle:" + this.clock.indexAt(tMs));
}
return OptionalDouble.of(SourceEvaluator.numeric(source, tMs, this.clock, rng));
```

按编号派生 + 可选按片派生，与求值顺序无关，新增/删除变量不扰动其它变量。构造时 `Map.copyOf(sources)`（第 37 行）做了防御性拷贝。

**1.4 纯函数性评估**：`SourceEvaluator.numeric` 本身无状态；但注入 `LifecycleCycleClock` 后，同一 `(source, tMs)` 在 start/stop/finish 前后结果不同——这是 D10/D11 的**有意设计**，与类注释"同一个 (source, 时刻) 永远得到同一个结果"（`SourceEvaluator.java:12-13`）在字面上冲突，契约应表述为"给定时钟状态"。另发现 `DataSource.ExprSource` 的 `vars` Map **未做防御性拷贝**（`DataSource.java:100-101`，无 compact constructor，对比 `CycleText` 第 151 行有 `List.copyOf`），调用方可在构造后改 map 破坏纯函数性（P3）。

**1.5 周期相位边界**：

- t=0：`CompiledCycleClock.indexAt(0)` 二分返回 0，`phaseAt(0)=0`，正确（`CompiledCycleClock.java:92-110`）。
- 周期切换：`startOf(i+1)=startOf(i)+durationOf(i)` 前缀和 + 二分 `table[mid] <= tMs`，边界时刻归入新片、相位归零，正确（`CompiledCycleClock.java:96-104, 129-132`）。
- **末片之后（RUNNING 态）**：`indexAt` 对 `t >= endMs` 返回 `count-1`（接口注释明说"停在最后一片"，`CycleClock.java:103-104`），于是

```java
@Override
public long phaseAt(long tMs) {
    return tMs - this.startOf(this.indexAt(tMs));
}
```

（`CompiledCycleClock.java:107-110`）——相位随 t 线性增长、**无界**，违反 `CycleClock.java:24-25` 写明的契约：

```java
 *   <li>{@code phase} 落在 {@code [0, duration)}，周期边界处归零——
 *       曲线按相位重放，不需要任何"复位"逻辑。</li>
```

`LifecycleCycleClock.phaseAt` RUNNING 分支直接委托（`LifecycleCycleClock.java:182`：`return this.compiled.phaseAt(tMs - this.startedAt);`），同样无界。缓解措施在 runtime：`CycleRunner.pump()` 于 `elapsed >= clock.endMs()` 时调 `clock.finish(now)`（`secs-runtime/.../session/CycleRunner.java:212-215`），无 cycle 段的 passthrough 也有 24h 冻结分支（`CycleRunner.java:177-181`）。因此 RUNNING 越界只存在于"越过 endMs 到下一次 pump tick"的窗口内；但直接使用编译期时钟的路径（草稿预览）无此保护。定级 P2。
- **FINISHED 冻结值越界**：`LifecycleCycleClock.finish` 把冻结相位设为**整段片长**而非末相位：

```java
public void finish(long nowMs) {
    this.frozenIndex = this.compiled.count() - 1;
    this.frozenPhase = this.compiled.durationOf(this.compiled.count() - 1);
    this.frozenElapsed = this.compiled.endMs();
    this.state = State.FINISHED;
}
```

（`LifecycleCycleClock.java:118-123`）——`frozenPhase == duration ∉ [0, duration)`。对周期曲线（如 Sine）这等价于下一片相位 0 的值，"冻结在末相位"实际冻结在末片的**右开端点**。定级 P2（应为 `durationOf(...) - 1` 或明确定义闭端点语义）。
- 负时间：`phaseAt(t<0)` 返回负值（indexAt 钳 0，phase = t - 0）；core 内调用方均传非负时刻，风险低（P3 备忘）。

**1.6 ContextTable** — `core/context/ContextTable.java:51-60`：重名显式抛错、`LinkedHashMap` 保序并注释了为什么不能 `Map.copyOf`（第 58 行），实现干净。但 `Def`（第 32-40 行）只校验 id 非空，**不校验 source 与文本语义的兼容性**；而 `SourceEvaluator.text` 对数值型来源静默返回空串：

```java
public static String text(DataSource source, long tMs, CycleClock clock) {
    return switch (source) {
        case DataSource.Text text -> text.value();
        case DataSource.CycleText text -> text.textAt(clock.indexAt(tMs));
        case DataSource.Const constant -> format(constant.value());
        default -> "";
    };
}
```

（`SourceEvaluator.java:74-81`）——若 Context 配了 `CurveSource`/`ExprSource`/`CycleNumber`，报文里得到 `""` 而非报错或数值文本。config 层是否拦截此组合本次未核实（见置信度）。定级 P2（带待核实前提）。### 2. EquipmentCompiler（core/compile/）

**2.1 DUPLICATE_SVID 检查存在且 first-wins** — 全局段 `EquipmentCompiler.java:102-108`、单元段 `193-199`：

```java
ResolvedSvid previous = this.svids.putIfAbsent(id, resolved);

if (previous != null) {
    this.issues.add(Issue.error("DUPLICATE_SVID",
            "SVID " + id + " 重复: " + previous.owner() + "." + previous.slot()
                    + " 与 " + unit.name() + "." + def.slot()));
}
```

RPTID / CEID 同样 `putIfAbsent` + ERROR（第 255-261、285-291、318-324 行）。全局 SVID 按名索引 first-wins 且重名给 WARNING（第 113-116 行 `DUPLICATE_GLOBAL_SVID_NAME`），注释明说取舍。

**2.2 单元重名 + reportByQualified last-wins（确认存在）** — 单元重名只报 WARNING：

```java
if (!this.unitNamesSeen.add(unit.name())) {
    this.issues.add(Issue.warning("DUPLICATE_UNIT_NAME", "单元名重复: " + unit.name()));
}
```

（`EquipmentCompiler.java:126-128`）；而限定报告索引是普通 `put`：

```java
this.reportByQualified.put(unit.name() + "." + def.slot(), resolved);
```

（`EquipmentCompiler.java:263`）——两个同名单元各定义 `UnitA.PROC_DONE` 时，`"UnitA.PROC_DONE"` 键被后编译单元**覆盖**（last-wins）。全局 CEID 的 `findReport` 优先命中限定索引（第 333-338 行），直接返回后者，**不会触发** `AMBIGUOUS_REPORT_REF`（该检查只覆盖短名多候选分支，第 343-347 行）。由于 WARNING 不阻断（`CompilationResult.ok()` 只看 ERROR，`CompilationResult.java:32-34`），带重名单元的配置可以正常启动，全局 CEID 报文引用到哪个单元的报告取决于声明顺序——正是任务提示的"限定报告引用歧义"。另外 `unitByCeid.put(id, unit.name())`（第 293 行）在 CEID 重复时也是 last-wins，与 `ceids` 表的 first-wins 不一致（P3）。定级 P1。

**2.3 ID 溢出防护存在** — 号段越界转 Issue 而非裸异常（第 162-172 行），int 溢出用 long 提升防护：

```java
if ((long) svidBase + unit.maxSvidOffset() > Integer.MAX_VALUE
        || (long) reportBase + unit.maxReportOffset() > Integer.MAX_VALUE
        || (long) ceidBase + unit.maxCeidOffset() > Integer.MAX_VALUE) {
    this.issues.add(Issue.error("ID_OUT_OF_RANGE", ...));
    return;
}
```

（`EquipmentCompiler.java:176-181`）。stride 重叠检查（137-151）、保留区间（96-98、188-190 等）、槽位/偏移重复（130-135、351-359）齐备。`reserveDuplicateOffsets` 只对 SVID 调用（第 184 行），report/ceid 的偏移重复靠 `duplicatedXxxSlots` 与 ID 去重间接覆盖，未逐一验证等价性（见置信度）。

### 3. BoundedValueSource：F4 钳制边界（确认为问题）

`core/value/BoundedValueSource.java:113-125`：

```java
case U8 -> new Bounds(0.0, Math.nextDown((double) Long.MAX_VALUE));
...
case I8 -> new Bounds((double) Long.MIN_VALUE, Math.nextDown((double) Long.MAX_VALUE));
case F4, F8 -> new Bounds(-Double.MAX_VALUE, Double.MAX_VALUE);
case ASCII, BOOLEAN, BINARY -> null;
```

F4 与 F8 共用 `±Double.MAX_VALUE`。F4（SECS 4 字节浮点）真实上界约 3.4028235e38（`Float.MAX_VALUE`），声明 F4 的 SVID 输出 1e100 时此处放行，越界推迟到协议编码层——恰是该类 Javadoc（第 16-22 行）宣称要消灭的失败模式。`Float.MAX_VALUE` 在本文件未出现。定级 P1。附带确认：NaN 归一 `Math.max(0.0, min)`（第 135-137 行）保证无符号类型不出负值；`+Inf` 被 `value > max` 钳到 max、`-Inf` 钳到 min，Inf 不会漏出；U8/I8 用 `Math.nextDown` 规避 `(double)Long.MAX_VALUE` 舍入到 2^63 的处理正确且有注释（第 24-27 行）。

### 4. v6 遗留 ValueStore / CurveValueSource（仍在，且有生产调用方）

两个类仍在 core：`core/value/ValueStore.java:22`（`public final class ValueStore`，内部 `ConcurrentHashMap<Integer, Binding>`，`bind` 重复绑定覆盖，第 42-44 行）、`core/value/CurveValueSource.java:11`（`public record CurveValueSource(ValueStore store) implements ValueSource`）。全仓 grep 生产调用方：

- `secs-app/.../config/ValueSources.java:70-72`：**有意保留的回退路径**——未配任何 source 时用默认曲线：

```java
ValueStore store = new ValueStore(config.spec().seed());
DefaultCurves.bind(store, config.spec().equipment(), equipment);
values = new CurveValueSource(store);
```

类注释（第 21-28 行）明说 v7 只剩两条路，模板图第三条已删。此路径最终同样包 `UnitIdValueSource` + `BoundedValueSource`（第 77-83 行），值域约束一致。
- `secs-runtime/.../session/HsmsSessionRuntime.java:290、295、850`：`new ValueStore(spec.seed())`、`new CurveValueSource(this.store)`（初值与热重载基线）；`HotReloader.java:109`：`new CurveValueSource(this.store)`；`RuntimeCatalog.java:32、45` 持有 store。即 runtime 会话运行时存在与 app `ValueSources` **并行的第二条装配管线**，是否已统一到 v7 `DataSourceValueSource` 需 runtime 维度确认（本次未读这些文件全文，仅 grep 行级证据）。定级 P2（架构残留/双路径漂移风险，非确证缺陷）。

### 5. 曲线纯函数抽查

- **Rng**（`core/curve/Rng.java`）：不可变 `final long seed`；`uniform01` 为 splitmix64 终混（第 46-49、109-114 行），`smooth01` 用 `Math.floorDiv`（第 60 行）——**负时间下网格取整正确**，frac ∈ [0,1)，无状态、与调用次数无关。`derive` FNV-1a + mix（第 36-38 行）。纯函数性成立。
- **RandomWalkCurve**（`core/curve/RandomWalkCurve.java:52-56`）：

```java
long t = Math.max(0L, tMs);
double drift = this.driftPerSec * (t / 1000.0);
double noise = this.sigma * rng.fbm(t, this.gridMs, this.octaves);
return this.policy.apply(this.start + drift + noise, this.min, this.max);
```

负时间钳 0（起点保持），噪声是时间纯函数而非逐 tick 累加，构造校验 min<max / sigma≥0 / gridMs≥1 / octaves≥1（第 36-49 行）。合格。
- **SineCurve**（`core/curve/SineCurve.java:34`）：

```java
double angle = 2.0 * Math.PI * ((tMs + this.phaseMs) % this.periodMs) / this.periodMs;
```

Java `%` 对负被除数返回负余数 → 负时间/负相位偏移时 angle 为负，但 sin 为奇函数且周期延拓连续，数学上仍是正确的周期函数（与 floorMod 写法等价），**非缺陷**；t=0 时值为 mid + amplitude·sin(2π·phaseMs%period/period)，行为确定。`tMs + phaseMs` 理论上可 long 溢出，需极端配置，P3 备忘。
- **表达式引擎**（`core/template/Expression.java`）：双闸齐备——`MAX_DEPTH = 64`（第 204 行，注释说明与 secs-json/stpParse 对齐）、`MAX_OPERATORS = 1024`（第 214 行，注释明确防左深加法链在 evaluate 栈溢出且 `catch RuntimeException` 接不住 Error），`countOperator`/`enter` 在解析期强制（第 335-347 行）。函数 arity 解析期校验（第 410-444 行），杜绝 `v[2]` 数组越界。除零：`case '/' -> a / b;`（第 144 行）走 IEEE 语义（±Inf/NaN），不抛错——依赖 `BoundedValueSource` 在取值处归一（NaN→max(0,min)，Inf→边界）；**未声明类型的 SVID 不钳制**（`BoundedValueSource.java:34、68-71`），此组合下 NaN/Inf 可流向编码层，是否有别处兜底未核实（P2，带前提）。`roundTo` 对步长 0 有显式分支（第 185 行）。

### 6. core 测试覆盖对照（src/test 全量 glob，14 个测试文件）

已有：ValueStoreTest、BoundedValueSourceTest、TracePlanTest、EngineClockTest、ExpressionTest、EquipmentCompilerTest、LifecycleCycleClockTest、TriggerAnchorTest、RngTest、RateLimiterTest、CurveTest、CurveGoldenTest、EventScheduleTest、ConditionDebouncerTest。

**v7 值模型类在 core 无直接单测**：`SourceEvaluator`、`DataSource`（六种 record 的校验/valueAt/textAt）、`DataSourceValueSource`、`ContextTable`、`CompiledCycleClock`、`UnitIdValueSource`。跨模块 grep（`SourceEvaluator|DataSourceValueSource|ContextTable|CompiledCycleClock|LifecycleCycleClock|DataSource\.`，src/test）仅命中 5 个文件：core 的 LifecycleCycleClockTest，app 的 CycleModelTest、ParamTemplateBodyTest，runtime 的 CycleRunnerTest、HsmsSessionRuntimeTest——即 v7 求值语义主要靠 app/runtime 集成测试间接覆盖，core 层缺相位边界（t=0、切换、末片后）、求值优先级、噪声派生稳定性的针对性单测。

## 问题清单

| 级别 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1 | F4 钳制上界用 Double.MAX_VALUE，越界推迟到协议编码层，违背该类自身设计目标 | BoundedValueSource.java:123 `case F4, F8 -> new Bounds(-Double.MAX_VALUE, Double.MAX_VALUE);` | F4 单列 `±Float.MAX_VALUE`（可再 nextDown 保守处理），补 F4 边界单测 |
| P1 | 单元重名仅 WARNING + reportByQualified last-wins：全局 CEID 限定引用静默取后编译单元的报告，可正常启动 | EquipmentCompiler.java:126-128（warning）、263（put）、333-338（限定优先命中）；CompilationResult.java:32-34（WARNING 不阻断） | DUPLICATE_UNIT_NAME 升级为 ERROR；或 reportByQualified 改 putIfAbsent+重名键报 AMBIGUOUS |
| P2 | RUNNING 态 t≥endMs 时 phaseAt 无界，违反 [0,duration) 契约；仅靠 runtime pump 转 FINISHED 收敛，存在 tick 窗口；预览路径无保护 | CompiledCycleClock.java:107-110；CycleClock.java:24-25 契约；LifecycleCycleClock.java:182；CycleRunner.java:212-215 | phaseAt 对超界时刻钳到末相位（duration-1），把不变量收回 core 而不是依赖调用方 |
| P2 | FINISHED 冻结相位 = durationOf(末片)，恰在 [0,duration) 之外，周期曲线冻结值等于下一片相位 0 | LifecycleCycleClock.java:120 `this.frozenPhase = this.compiled.durationOf(this.compiled.count() - 1);` | 改为 duration-1，或在契约中明确定义末片闭端点语义并同步注释 |
| P2 | runtime 仍存在 ValueStore/CurveValueSource 第二装配管线，与 app ValueSources（v7 统一入口）并行，存在漂移风险 | HsmsSessionRuntime.java:290/295/850、HotReloader.java:109、RuntimeCatalog.java:32/45（grep 行级证据）；ValueSources.java:70-72 为有意回退 | runtime 维度确认后收敛到 ValueSources；ValueStore/CurveValueSource 标注 @Deprecated 或移入 default-curves 专用包 |
| P2（带前提） | ContextTable.Def 不限制 source 种类，数值型 source 的 Context 在报文中静默变 ""；未声明类型 SVID 的表达式 NaN/Inf 无钳制 | ContextTable.java:32-40；SourceEvaluator.java:74-81 `default -> ""`；BoundedValueSource.java:68-71 | config 层校验 Context 只允许 Text/CycleText/Const（需先核实是否已有）；评估对无类型 SVID 的 NaN 兜底 |
| P3 | ExprSource.vars 无防御性拷贝，构造后可被外部修改，破坏纯函数承诺（对比 CycleText 有 List.copyOf） | DataSource.java:100-101 vs 151 | compact constructor 中 `vars = Map.copyOf(vars)` |
| P3 | CEID 重复时 ceids 表 first-wins 但 unitByCeid last-wins，两表归属可能不一致 | EquipmentCompiler.java:285-293 | unitByCeid 改 putIfAbsent，与主表一致 |
| P3 | SourceEvaluator 类注释"同一 (source,时刻) 永远同结果"与生命周期时钟可变状态字面冲突；SineCurve tMs+phaseMs 理论 long 溢出；负 t 时 phaseAt 返回负值 | SourceEvaluator.java:12-13；SineCurve.java:34；CompiledCycleClock.java:107-110 | 注释改为"给定时钟状态"；溢出/负时间按需加防护或文档化 |

## 亮点

- **表达式双闸**（Expression.java:204-214、335-347）：深度与运算符数分别设限，注释准确指出左深树"解析不递归、求值递归"的栈溢出路径及 Error 不可捕获问题，防护动机与实现都对。
- **U8/I8 的 `Math.nextDown((double) Long.MAX_VALUE)`**（BoundedValueSource.java:118/122）：正确处理 double→long 舍入到 2^63 的经典陷阱，且有精度上限说明。
- **ID 溢出用 long 提升检查并把越界转为字段级 Issue**（EquipmentCompiler.java:167-181），符合"compile 一次报全"契约。
- **CompiledCycleClock 惰性前缀表**（124-135）：volatile + 幂等重建，无锁并发安全且结果确定；抖动用 `rng.derive("cycle:"+i)` 与求值顺序无关。
- **Rng.smooth01 用 floorDiv**（Rng.java:60）保证负时间网格正确；ContextTable 保序注释（58 行）与重名显式报错干净利落。
- 注释质量整体很高：设计决策（D9-D12、审计 P1-1、评审 HIGH-1）在代码处留痕，CycleRunner.pump 的互斥与补发顺序注释（168-203 行）解释了真实踩过的坑。

## 测试覆盖评估

- **覆盖良好**：曲线族（CurveTest + CurveGoldenTest + RngTest）、表达式（ExpressionTest）、编译器（EquipmentCompilerTest）、生命周期时钟（LifecycleCycleClockTest）、事件调度（EventScheduleTest / TriggerAnchorTest / RateLimiterTest / ConditionDebouncerTest）、BoundedValueSource、ValueStore（v6 遗留反而有测试）。
- **缺口（core 层零直接单测）**：SourceEvaluator（求值优先级、V/t 变换、局部变量派生）、DataSource 各 record（CycleNumber.valueAt / CycleText.textAt 补零与轮换、compact 校验）、DataSourceValueSource（noisePerWafer 派生稳定性）、ContextTable（保序、重名、snapshotAt）、CompiledCycleClock（t=0/切换/末片后相位边界）、UnitIdValueSource。间接覆盖仅见于 app/runtime 5 个测试文件（grep 证据）。
- 建议优先补：CompiledCycleClock 相位边界性质测试（∀t: 0≤phaseAt(t)<durationOf(indexAt(t))，当前实现会红）、SourceEvaluator 六种来源×三种时钟矩阵、F4 钳制边界。

## 置信度说明

**已核实（逐字读取）**：DataSource、SourceEvaluator、DataSourceValueSource、ContextTable、CycleClock、CompiledCycleClock、LifecycleCycleClock、BoundedValueSource、EquipmentCompiler（全文 365 行）、CompilationResult、Rng、RandomWalkCurve、SineCurve、Expression（全文 467 行）、Curve 接口、ValueSources（app，全文 105 行）、ValueStore（前 60 行）、CycleRunner.java:140-219。测试清单来自 src/test 全量 glob；跨模块引用来自 grep 行级证据。

**未核实项**：
1. ADR-0003 / ADR-0020 / svid-value-model.md 文档原文未读取，与文档的一致性判断基于任务背景描述与代码注释。
2. HsmsSessionRuntime / HotReloader / RuntimeCatalog 中 ValueStore 管线的完整语义（是否已接 v7 source、两管线何时各自生效）只有 grep 行级证据，未读文件全文——P2"双管线"结论的严重度待 runtime 维度确认。
3. config 层（SessionConfig / schema 解析）是否拦截"Context 配数值型 source"与"无类型 SVID"组合未核实，相关 P2 带前提。
4. `duplicatedReportSlots/duplicatedCeidSlots`（UnitSpec）是否完全覆盖 report/ceid 偏移重复未读源码验证。
5. 其余曲线（ConstantCurve/RampCurve/UniformCurve/StepCurve/PwlCurve/SequenceCurve）与 event 包、trace 包未逐一评审（任务范围外抽查项）。
6. 未运行任何构建/测试，全部结论为静态阅读证据；"零直接单测"结论限于 glob 到的文件名与关键词 grep，若存在不含类名的间接断言可能低估覆盖。