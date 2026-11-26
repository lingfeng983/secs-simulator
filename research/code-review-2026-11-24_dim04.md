# secs-runtime 运行时装配层专项评审（维度 04：并发 / 生命周期 / 热重载 / Trace / 压测）

- 评审日期：2026-11-24
- 范围：`secs-simulator-service/secs-runtime/src/main/java`（80 文件），重点 HsmsSessionRuntime 及协作者、SessionLauncher、RuntimeContext/RuntimeApi、HostSimRegistry、BurstRunner、Bootstrap 停机路径；测试覆盖参考 secs-runtime/src/test（29 文件）与 secs-cli/src/test（26 文件）。
- 方法：只读通读 + grep 采样；所有结论附 `文件:行号` 与逐字摘录；未读到的部分明确标注"未核实"。

## 现状概述

装配层已按"谁拥有状态"完成拆分：`RuntimeContext` 是 ApiContext 门面 + 停机编排（RuntimeContext.java:24-41），会话生命周期集中在 `SessionLauncher`（全局 ReentrantLock 串行化 create/reload/close），单会话内部再拆为 SessionLifecycle / EventScheduler / EventReporter / TracePump / PrimaryDispatcher / HotReloader 等协作者。热重载有三档语义：指纹相同走 `applyHot` 全量热替换（全停）、分段保存走 `applySections` 最小生效（保留未改动计划启用态与钉值）、连接标识变化走关旧开新 + lastGood 快照回滚。3.0.1 声明的两处历史缺陷（Trace 重连停发、重载失败丢运行态编辑）均能在代码中找到对应修复与解释性注释。整体并发设计成熟：每会话独立线程、锁粒度明确、注释大量记录历史竞态教训。未发现 P0 级问题；发现 1 个 P1（重载 RuntimeException 路径不回滚运行态编辑）与若干 P2/P3。

## 线程模型

均为**平台线程**（采样范围内未见虚拟线程；全仓是否使用 `newVirtualThreadPerTaskExecutor` 未核实，grep 采样命中 200 条上限截断）：

1. **事件心跳**：每会话 1 条 `ScheduledExecutorService`，100ms 固定节拍，daemon。
   - EventTicker.java:18 `private static final long TICK_MS = 100L;`
   - EventTicker.java:47-53 `Executors.newSingleThreadScheduledExecutor(...)` + `created.scheduleAtFixedRate(pump, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);`
   - 手动时钟（测试）不起线程：EventTicker.java:43-45 `if (manualClock) { return; }`
2. **ACK 等待线程**：每会话 3 条单线程有界队列（S6F11 / S6F1 / S5F1 各一），队列容量 256，daemon。
   - SessionLifecycle.java:28 `private static final int ACK_QUEUE_CAPACITY = 256;`
   - SessionLifecycle.java:70-76 `new ThreadPoolExecutor(1, 1, 0L, ..., new LinkedBlockingQueue<>(ACK_QUEUE_CAPACITY), ... thread.setDaemon(true);`
   - 分池理由（防报警堵死事件流）：HsmsSessionRuntime.java:173-174 "共用一个 ACK 线程时，主机不回 S5F2 会让后续所有 S6F11 的 W 位发送排队，队列满后成批丢弃采集事件"。
3. **协议 I/O 线程**：由 secs 库（HsmsSsCommunicator）持有，收帧回调进入 PrimaryDispatcher；`HsmsConnector.open` 注册 pass-through / receive / state 监听（HsmsConnector.java:93-96）。库内线程模型未核实（外部依赖）。
4. **压测**：全局固定 2 线程 + 容量 4 的有界队列，满则拒绝（burst-busy），5 分钟预算。
   - BurstRunner.java:34-40 `BURST_BUDGET_MS = 5 * 60_000L;` / `BURST_THREADS = 2;` / `BURST_QUEUE = 4;`
   - BurstRunner.java:64-72 `new ThreadPoolExecutor(BURST_THREADS, BURST_THREADS, 30L, ..., new ArrayBlockingQueue<>(BURST_QUEUE), ...)` + `allowCoreThreadTimeOut(true)`。
5. **主机模拟器**：每实例建链用一次性 daemon opener 线程 + 6s join 超时（HostSimulator.java:58 `CONNECT_TIMEOUT_MS = 6_000L;`、429-447）；autoReconnect 用每实例单线程调度器指数退避（HostSimulator.java:320-338，5s 起、上限 60s）；采集器每份计划一条单线程调度器（HostCollector.java:187）。
6. **HTTP 请求线程**：参数校验与状态写入在请求线程完成，长耗时（压测 sleep、W 位等 ACK、建链等待）全部移出（BurstRunner.java:28-29、HsmsSessionRuntime.java:166-176）。

**共享状态可见性**（grep 采样）：模型/组装器/值来源/communicator/dispatcher 等热替换点均为 volatile（HsmsSessionRuntime.java:60,75,102,105,108,163-176；ValueSourceHandle.java:15；TracePump.java:31；EventScheduler.java:32）；计数器用 AtomicLong/AtomicReference；集合用 ConcurrentHashMap/newKeySet（EventScheduler.java:82-97）；常量表 `Collections.synchronizedMap`（HsmsSessionRuntime.java:277）。

## 逐项核查（证据）

### 1. 会话关闭与在途报文竞态

- open/disconnect/close 互斥于 `lifecycleLock`，注释明确记录历史双链路泄漏：SessionLifecycle.java:35-38 "两个并发「连接」…各建一条链路；后写者覆盖字段，先建的那条失去唯一引用，socket、协议线程、端口全部泄漏"。
- 链路相位读改写单独用 `phaseLock`，通知在锁外发：SessionLifecycle.java:111-130。
- open 返回前的早到报文用 gate 缓存、dispatcher 就绪后按序补发，且取局部引用防 disconnect 置空：SessionLifecycle.java:184-195 "取到局部变量再出锁用：出锁后 dispatcher 可能被 disconnect() 置空"。
- **残留竞态**：dispatcher 就绪后收到的在途报文，close 将 `communicator` 置 null（SessionLifecycle.java:286-288），而 `SessionSender.reply/send` 直接 `this.host.communicator.send(...)` 无空检查（SessionSender.java:23,32）→ 关闭窗口内 NPE；被 PrimaryDispatcher.dispatch 的 catch(Exception) 吞掉记 lastError（PrimaryDispatcher.java:203-206），不崩溃但产生噪音错误。见问题清单 P3-2。
- open 失败路径完整回收：connector.open 成功后构造失败时关闭 communicator（SessionLifecycle.java:242-253）；HsmsConnector.open 内 open 失败也 close（HsmsConnector.java:98-107）。

### 2. 端口绑定/解绑原子性

- 进程内：`SessionLauncher.lifecycleLock` 使 find→端口检查→open→register 原子（SessionLauncher.java:59-67）；`requirePortFree` 挡进程内重复端口（SessionLauncher.java:515-528）。
- 跨进程：被动端 open 前用无 SO_REUSEADDR 的 ServerSocket 试绑（HsmsConnector.java:119-130）。**试绑与实际 bind 之间存在 TOCTOU 窗口**（probe close 后 comm.open 前），跨进程抢占仍可能；注释自认只探测"外部进程是否占着"。P3-3。
- 同端口重建"先关旧再开新"，失败用 lastGood 快照恢复（SessionLauncher.java:204-208, 287-332）；恢复再失败时关闭已 open 未注册的实例防端口永久泄漏（SessionLauncher.java:321-330）。

### 3. 生命周期资源释放

- close/disconnect：停 ticker → shutdownNow 三个 ACK 池 → comm.close → 清 communicator/dispatcher/terminalInbox → 状态 CLOSED（SessionLifecycle.java:132-156, 258-292）。ticker.stop 用 shutdownNow（EventTicker.java:66-72），start 幂等先停旧（EventTicker.java:41-58，注释记录旧写法 daemon 线程泄漏教训）。
- reconnect 复用对象：`openLocked` 检测 `isShutdown()` 重建 ACK 池（SessionLifecycle.java:165-173）。
- **关闭后调度任务残留**：ticker 与 ACK 池均 shutdownNow，pumpNow 不再被驱动；未发现关闭后仍跑的定时任务。唯一例外是压测任务（见第 6 节）。
- 停机：`Bootstrap` cleanup 用 AtomicBoolean 保证只执行一次，shutdown hook 内做真正关闭（Bootstrap.java:283-328），顺序 sessions.closeAll → probe → runtimeContext.close（hosts+bursts）→ api.close → 报文/审计日志 → planFired → DB（Bootstrap.java:295-322）。`RuntimeContext.close` 关主机连接与压测执行器（RuntimeContext.java:111-115）；BurstRunner.close 先置全部 cancelRequested 再 shutdownNow（BurstRunner.java:358-371）。`SessionManager.closeAll/remove` 内部实现未核实（不在本次读取范围）。### 4. 热重载与运行中事件/Trace 的竞态

- **先编译再动旧会话**：`reload` 先 `prepare(...)`（解析+编译），失败抛出时旧会话未动（SessionLauncher.java:175-179 "先构造新的：编译不过时旧会话还好端端地跑着"）。
- 热路径与心跳的互斥：`applyHot` → `enableSchedule` 是 `synchronized`，与 `pumpEvents`（synchronized）同监视器（EventScheduler.java:226-228 "pump 与本方法互斥（同一监视器），但把 schedule 的发布放到状态就绪之后"）。
- **非原子窗口**：`HotReloader.apply` 顺序执行 W 位设置→refreshModelViews→dataModel→enableSchedule→values.set（HotReloader.java:95-111），各步分别加锁但整体不是一把锁；期间心跳可能用"新模型+旧编排"或"新编排+旧值来源"跑一个 tick。由于 enableSchedule 全停（所有计划禁用，EventScheduler.java:236-239），实际可发出的只有 Trace/手动路径，风险低但存在短暂混合快照。P3-4。
- pumpNow 整体互斥防"到点却没发"：HsmsSessionRuntime.java:1017-1026 "它要改 lastFireMs、条件去抖状态、延迟队列——这些状态彼此关联，分开加锁挡不住交错"。
- **Trace 与热重载**：`TracePump.traces` volatile + `reset` synchronized，注释记录历史缺陷"handleInitialize 把新 TRID 装进即将被丢弃的旧管理器，S2F24 回成功但永远不推 S6F1"（TracePump.java:26-31）；reset 先 stopAll 再新建并带上容量限制（TracePump.java:175-181）。TraceManager 所有公共方法 synchronized（TraceManager.java:128,135,145,164,210）。
- **重载期间报文丢/重**：热路径不断链，报文不丢；同端口重建路径 close→open 之间的到达报文由 TCP/主机 T5 重试兜底（库行为未核实），设备侧无补发（设计如此，ADR 0011/EventReporter.java:279-280 "不在线的触发在调度层就被清场丢弃（D8），发送失败也不重试不补发"）。重建后 communicator 换新，无旧连接报文投给新 dispatcher 的路径（gate 局部引用 + close 置 null）。

### 5. 运行态编辑恢复（对照 3.0.1 修复声明与 runtime.db）

- 重载 = 先抓快照→清库→reload→失败回滚：RuntimeApi.java:476-490 "清之前先抓快照：重建可能因配置非法/端口被占失败…不回滚等于'重载失败一次，钉值全丢'"；失败时 `persister.restore(deviceId, before)` + `persister.replay(hsms, deviceId)` 把编辑重播到被拉回的旧会话（RuntimePersister.java:154-163 "回滚只把行写回库…不补这一步，界面会显示钉值已丢，而库里还留着"）。**与 3.0.1 修复声明一致，已核实。**
- **缺口**：catch 仅 `ConfigException | IOException`（RuntimeApi.java:483）。`launcher.reload` 的注册失败路径抛 RuntimeException（SessionLauncher.java:219-224），此时编辑已清、不会走 restore/replay → 运行态编辑丢失。见 P1-1。
- 重启恢复：注册观察者按依赖序重放目录编辑→计划覆盖→钉值（Bootstrap.java:365-413），单条失败跳过不整体放弃；**计划启用态不跨重启回放**（Bootstrap.java:414-416 "计划启用态不再跨重启/重建回放（2026-11-16 设计 D4）"）。注意 RuntimePersister.clearEdits 的注释仍说"计划启用态不清——…断开重连后要保留"（RuntimePersister.java:84），与 D4 口径存在文档级不一致（断开重连确实保留内存态，重建则全停），P3-5。
- 分段生效的钉值迁移：只搬新模型仍存在的 SVID，删掉的计数上报（HsmsSessionRuntime.java:844-864）；`applyHot` 后重新包 PinnedValueSource 并 set 回句柄（HsmsSessionRuntime.java:733-739，评审 HIGH-3 修复注释在案）。
- ADR 0011 对照：禁用清去抖器与延迟队列（EventScheduler.java:462-468）、firePlan 统一兜底（EventScheduler.java:694-705）、recordFire 不给禁用目标入队（EventScheduler.java:718-727）、离线清场不补发（EventScheduler.java:610-617）——**全部落实**。ADR 中"重载后恢复配置里的启用状态"已被 3.0 语义（默认全禁用）取代（EventScheduler.java:232-239），ADR 文本未更新，属文档滞后。
- ADR 0019（不自动启动会话）：`enableSchedule` 默认全禁用（EventScheduler.java:236-239）、cycle 初态 STOPPED（SessionLauncher.java:454-455）、主机实例装回内存全部断开态不自动连接（HostSimRegistry.java:451-459）——一致。0019 原文未读取，未核实其具体条款。

### 6. Trace 与事件门控

- **ONLINE 门控位置**：`ControlStateHolder.canSendUnsolicited()` = `communicatable && state == ONLINE`（ControlStateHolder.java:75-77）；会话层 `canSendUnsolicited` 注入 scheduler/tracePump/cycleRunner（HsmsSessionRuntime.java:315,336,413）；计划启用另有 409 门控（HsmsSessionRuntime.java:631-637）；S6F11 台账路径的 onlineCheck（HsmsSessionRuntime.java:1118-1119）。
- **重连后 Trace 恢复**：3.0.1 "Trace 重连停发"修复 = ACK 池改为 supplier 每次现取（TracePump.java:36-41 "抓着一个已关闭的池，重连后每次 execute 都抛 RejectedExecutionException…S6F1 再也发不出来"；TracePump.java:266-273 pump 内 `this.ackSenders.get()` + isShutdown 检查丢弃计数）。注意：disconnect 不重建 TraceManager（traces 定义保留），reconnect 后 pump 继续按原 TRID 采样——采样期间掉线的帧因 canSend=false 被丢弃（TracePump.java:253-255，poll 已消费样本），即**掉线窗口内的 Trace 样本丢失且不补**，与"不补发"总口径一致。
- **S2F23 多 TRID**：LinkedHashMap<Integer,ActiveTrace>，同 TRID 重init替换（TraceManager.java:199-201）、超容量回 TIAACK_TRACES_DENIED（188-190）、TOTSMP=0 终止（166-169）、totsmp>1,000,000 拒绝（50,173-176）、DSPER<1000ms 拒绝（177-179）。默认 1 条 ≤10 SVID 来自 TraceLimits（TraceManager.java:44-47），可经 `traceLimits()` 配置且热重载带走（TracePump.java:118-125、SessionModelViews.java:44-47）。
- **积压保护**：单次 poll 补齐上限 10,000 样本，超限快进并保末帧（TraceManager.java:59,224-242）。
- **限流**：设备级 SessionRateGate（volatile RateLimiter，1s 窗口），事件编排与工艺周期共用（SessionRateGate.java:6-21）；所有触发路径经 firePlan 过闸（EventScheduler.java:694-705）；手动 emitCeid 不受限流（HsmsSessionRuntime.java:1040-1048，注释明示是操作员显式动作）；单步试跑不占额度（EventReporter.java:249-251）。AfterEvent 链防环 64 跳（EventScheduler.java:745-748）。
- **幂等**：PlanExecutor 用 `ConcurrentMap.compute` 原子预留幂等键（含 CEID），60s 窗口，批量清理防 O(n²)（PlanExecutor.java:167-195）。

### 7. BurstRunner 与 HostSimRegistry

- 压测上限：count 1..5000、interval 0..60000ms、总预算 5 分钟、每目标同时 1 个（`state.running` synchronized 检查，BurstRunner.java:159-167）、全局 2 线程 + 队列 4 满拒（201-204 burst-busy）。参数校验/SML 解析在请求线程（BurstRunner.java:121,278-284）。中断恢复标志并中止整轮（446-453）；finally 保证 running 复位（196-199）。
- 会话关闭联动取消：RuntimeApi.closeSession → bursts.cancel（RuntimeApi.java:447）；主机断开联动 cancelHost（RuntimeContext.java:279-282）。cancel 只置 volatile 标志，任务在下一轮检查退出（BurstRunner.java:220-225,429-432）——若正卡在 `pace` 的长 sleep（interval 最大 60s），取消最多延迟一个 interval 生效；close() 用 shutdownNow 中断则立即。可接受，P3 备注。
- **忙态字段可见性**：`state.running=false` 在任务 finally 中裸写（BurstRunner.java:198,310），字段 volatile 保证可见；startedAt/count 先写后置 running（163-166 注释）。
- 主机实例：**数量不设上限**（HostSimRegistry.java:24 "实例按持久 hostId 全局唯一寻址，数量不设上限"）——每实例可各持 1 条协议连接 + 1 条 retry 调度线程 + N 条采集线程，无全局资源帽。P2-2。
- 并发打开：open 方法 synchronized + putIfAbsent 兜底（HostSimRegistry.java:64,124-125）；但 attach 失败方"把自己的连接关掉"的注释承诺（HostSimRegistry.java:29-30）在 attach 代码中未见对落败 sim 的显式关闭——putIfAbsent 返回 previous 时新建 sim 尚未 connect（connect 在 open 里、用的是返回值），故无连接泄漏；仅对象丢弃。已核实无泄漏。
- 远程连接超时：6s join + failConnect 统一收尾（关连接、清字段、置 FAILED、排退避），注释详述旧写法三重后果（HostSimulator.java:423-426,465-495）；reconnect 两段同锁防挤入（541-547）；connectById 幂等处理 CONNECTED/CONNECTING（HostSimRegistry.java:219-223）。
- **超时线程残留**：opener 超时后 `comm.close()` 期望使 `openAndWaitUntilCommunicating()` 返回，opener 是 daemon 不阻塞 JVM；是否必然退出取决于库实现，未核实。

### 8. 其它采样发现

- `HsmsSessionRuntime.emitted/wbitDropped`（160-161）声明后未见使用（计数实际在 EventReporter），疑似死字段。P3-6。
- `SessionLifecycle.lastFireMs`（63）仅 startTicker 写入，调度真值在 EventScheduler.lastFireMs——冗余状态，注释自认"目前只由 ticker 线程写"。P3-6。
- `EventScheduler.replaceSchedule`（596-605）非 synchronized，但调用方 add/update/removeEventPlan 均 synchronized，实际安全；若未来直接调用有隐患。
- HostSimRegistry.java:13-15 `HsmsConnectionTarget` import 重复两次（编译应为错误或警告，实际构建状态未核实）。
- TerminalInbox 有界 20 条 + synchronized deque（TerminalInbox.java:23,54-81）；S10F4 显式 secondary 而非 gem().s10f4（89-95，注释记录 T3 教训）。
- ControlStateHolder.change 的 check-then-set 非原子（58-63），两条线程并发迁移可能双发通知/丢通知；状态本身 volatile 可见。窗口极小，P3-7。## 问题清单

### P1

**P1-1 重载抛 RuntimeException 时运行态编辑不回滚（3.0.1 修复的残留缺口）**
- 证据：RuntimeApi.java:480-490
  `this.persister.clearEdits(deviceId);` → `try { this.launcher.reload(sessionId); } catch (ConfigException | IOException failure) { this.persister.restore(deviceId, before); ... }`
  而 SessionLauncher.java:219-224 目标变更路径注册失败时 `catch (RuntimeException e) { this.sources.remove(id); opened.close(); ... throw e; }`——RuntimeException 不在 catch 范围，编辑已清且不 restore/replay。
- 影响：与 3.0.1 "重载失败丢运行态编辑"同症状，触发面收窄为"注册失败/管理器已关"等 RuntimeException 场景；且此时旧会话已被 close（SessionLauncher.java:215 `this.close(id)`），设备也一并消失，比声明的"失败旧会话原样跑"更糟——目标变更路径是"先开新再关旧"，但注册失败分支没有把旧会话拉回。
- 建议：catch 扩为 `ConfigException | IOException | RuntimeException`；目标变更路径注册失败时考虑用 lastGood 恢复旧会话（与 rebuildInPlace 对齐）。

### P2

**P2-1 热重载多步替换非原子，存在一个 tick 的混合快照**
- 证据：HotReloader.java:95-111 依次 `collectionEventWbit.accept → traceWbit.accept → refreshModelViews.accept → dataModel.accept → enableSchedule.accept → values.set`，各步独立加锁；pumpNow 只锁 pumpLock（HsmsSessionRuntime.java:1026），与 HotReloader 无共同监视器。
- 影响：心跳可在两步之间用新组装器+旧值来源（或反之）发出一帧；因 enableSchedule 全停，事件面风险低，Trace/手动路径仍可发。
- 建议：HotReloader.apply 全程持 pumpLock（或由会话层包一层 synchronized(pumpLock)），使替换与 pump 互斥。

**P2-2 主机实例数量无上限，线程/连接资源无全局帽**
- 证据：HostSimRegistry.java:24 "数量不设上限"；每实例 retry 调度线程（HostSimulator.java:320）、每采集计划一条调度线程（HostCollector.java:187）、opener 线程（HostSimulator.java:429）。
- 影响：API 可无限建实例（含 connect=false 的壳），远程不可达目标叠加 autoReconnect 时每实例长期持有退避线程与重试连接尝试；嵌入式/受限宿主可被拖垮。
- 建议：加实例总数上限与"活跃连接数"帽，或至少对 autoReconnect 实例总数限流。

### P3

**P3-1 重载失败回滚的库写失败静默降级**：RuntimePersister.snapshot 读库失败返回 null（RuntimePersister.java:108-111 "降级：失败时无法回滚"）、restore/replay 各条 guard 吞异常仅计数（183-190）。设计取舍已在 /health persistFailures 暴露（Bootstrap.java:266），可接受但需运维知晓。

**P3-2 关闭窗口在途报文 NPE 噪音**：SessionSender.java:23,32 `this.host.communicator.send(...)` 无空检查；close 置 null（SessionLifecycle.java:286）后在途 dispatch 抛 NPE，被 PrimaryDispatcher.java:203-206 catch 记入 lastError。建议 sender 取局部引用判空静默丢弃。

**P3-3 端口试绑 TOCTOU**：HsmsConnector.java:124-127 probe 关闭与真实 bind 之间可被外部进程抢占；进程内已由 lifecycleLock+requirePortFree 覆盖，跨进程窗口无法根除，建议 bind 失败时给出与预检一致的错误文案（现已有 FAILED 路径兜底）。

**P3-4 applyHot 钉值语义与文档**：PinnedValueSource.java:14-15 "热重载或重启即失效"，applyHot 确实不迁移钉值（HsmsSessionRuntime.java:736-739 新建空包装）；applySections 则迁移（844-864）。两档语义不同是正确的，但全量热重载丢钉值时 runtime.db 的 pins 行已被 clearEdits 清掉（RuntimeApi.java:480），行为一致；仅需在 UI 提示"整份重载会丢钉值"。

**P3-5 注释/ADR 口径滞后**：RuntimePersister.java:84 "计划启用态不清…要保留" vs Bootstrap.java:414-416 D4 不回放；ADR 0011 "重载后恢复配置里的启用状态" vs EventScheduler.java:236-239 默认全禁用。建议更新文档。

**P3-6 死字段/冗余状态**：HsmsSessionRuntime.java:160-161 `emitted/wbitDropped` 未见使用；SessionLifecycle.java:63 `lastFireMs` 与 EventScheduler.lastFireMs 重复；HostSimRegistry.java:13-15 重复 import。

**P3-7 ControlStateHolder.change 非原子 check-then-set**（ControlStateHolder.java:58-63）：并发迁移可能重复/丢失 onChange 通知；建议 synchronized 或 AtomicReference.getAndSet。

**P3-8 压测取消延迟**：cancel 只置标志（BurstRunner.java:220-225），长 interval 的 sleep 中最多延迟 60s 退出（会话级 close 不走 shutdownNow）；可接受，记录备查。

未发现 P0。

## 亮点

1. **注释即事故档案**：几乎每处锁/volatile/supplier 都附带历史缺陷的因果链（SessionLifecycle.java:35-38 双链路泄漏、TracePump.java:26-41 重连停发、EventTicker.java:33-36 心跳泄漏、HostSimulator.java:465-472 failConnect 三重后果、PlanExecutor.java:173-194 幂等原子性与 O(n²) 清理），可维护性极高。
2. **三档热重载语义清晰**：指纹热替换（全停）/分段最小生效（保启用态+钉值迁移+plansKept/pinsDropped 上报）/连接变更重建（lastGood 回滚），且"先编译再动旧会话"贯彻到位（SessionLauncher.java:175-179）。
3. **ACK 三池分离**防单类报文堵死事件流（HsmsSessionRuntime.java:170-176）；W 位等待全部移出心跳与 HTTP 线程。
4. **Trace 采样数学正确**：t0+k×DSPER 纯函数补算、积压快进保末帧、totsmp/SVID/条数三重容量闸（TraceManager.java:23-27,49-59,164-201,224-242）。
5. **压测工程化**：有界队列+忙态拒绝+预算+取消联动+中断语义+首错采样防日志刷爆（BurstRunner.java:446-462）。
6. **停机编排一次性、有序、hook 内完成 I/O**（Bootstrap.java:283-328），避免 JVM halt 截断日志尾行。
7. 运行态恢复按依赖序重放且单条失败不整体放弃（Bootstrap.java:365-373）。

## 测试覆盖评估

- secs-runtime/src/test 29 文件（glob 已核实清单）：
  - 热重载：HsmsSessionRuntimeTest 覆盖 applyHot 换表不断链、null 值来源回落、Context/Ecid 替换、钉值全停（HsmsSessionRuntimeTest.java:33,63,82,107）；SessionLauncherTest 覆盖同端口重建、只改事件/设备保持同实例、session 段变化换实例（SessionLauncherTest.java:141,184,203,222）。
  - 并发专项：PlanFiredThrottleTest.concurrentPublishNeverLosesItems（:124）是唯一显式命名的并发测试；PlanExecutorTest 覆盖 W 位 ACK 队列与台账离线冲刷（:279,314,403）。
  - Trace：TraceManagerTest 含快进末帧（:122）；EventSchedulerTest/EventReporterTest/CycleRunnerTest/ControlStateHolderTest 存在（文件级核实，内部用例未逐一读取）。
- secs-cli/src/test 26 文件为端到端：SessionTraceTest、SessionControlStateTest、SessionEventControlTest、SessionSelectedLinkSurvivesRogueHostTest、SessionAfterEventTest、SessionOnConditionTest 等覆盖协议级行为（文件级核实）。
- **缺口**：
  1. 未见"close 与在途报文并发"、"disconnect/reconnect 与 pump 交错"、"并发 open 双点击"的竞态回归测试（grep 测试方法名采样未命中；受限于采样，不能断言不存在）。
  2. 未见"重载失败回滚运行态编辑"（RuntimeApi.reloadSession 的 snapshot/restore/replay 链路）的直接测试——RuntimeRestoreTest 存在但内容未读取，未核实是否覆盖失败分支，尤其 P1-1 的 RuntimeException 分支。
  3. 未见 BurstRunner 取消/预算/忙态的专项测试文件（29 文件清单中无 BurstRunnerTest）。
  4. HostSimulator 连接超时/退避/failConnect 的测试仅见 HostCollectorCeidTest（采集 CEID），连接生命周期路径未核实。
- 总体：单线程确定性路径（手动时钟 + pumpNow 步进）覆盖扎实，这正是 EventTicker 手动时钟设计的红利；真实多线程竞态路径主要靠代码审查与注释中的历史修复背书，自动化回归薄弱。

## 置信度说明

- **高置信**（通读全文）：HsmsSessionRuntime、SessionLifecycle、EventScheduler、EventTicker、TracePump、TraceManager、EventReporter、PrimaryDispatcher、HotReloader、HsmsConnector、SessionLauncher（前 700 行）、BurstRunner、HostSimRegistry、RuntimePersister、ControlStateHolder、PinnedValueSource、SessionSender、TerminalInbox、SessionRateGate、SessionModelViews、ADR 0011。
- **中置信**（读关键片段）：RuntimeApi（close/reload 段）、Bootstrap（注册观察者与停机段）、HostSimulator（连接/退避/断链段）、PlanExecutor（前 260 行）、DeviceV2Delegates（链路与重载委托段）、RuntimeContext（前 700 行）。
- **未核实**：SessionManager、HsmsSsCommunicator 库内线程与 close 语义、CycleRunner/AlarmReporter/HostCommandHandler/PreviewBuilder/RuntimeCatalog/RuntimeEdits 全文（仅 grep 采样锁声明）、HostCollector 全文、SessionLauncher 701-749 行、RuntimeContext 701-803 行、PlanExecutor 261-428 行、ADR 0019 原文、全部测试文件正文、虚拟线程全仓排查（grep 200 条截断）、编译/测试实际运行（本任务只读，未执行构建）。
- 行号以本次 read_file 输出为准；grep 采样命中上限处已注明，不据此断言"全仓不存在"。