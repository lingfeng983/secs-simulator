# 0016 · 目录与分层收敛

- 状态：已接受
- 日期：2026-11-09

## 背景

功能已完整，但仓库与源码的分层在长期迭代中变得含糊：

- 根目录混着历史产物（`.class`、旧安装包、`test-run/`）、被 `docs/` 取代的 `templates/`；
- 前端包管理器同时存在 `package-lock.json` 与 `pnpm-lock.yaml`，脚本与文档各写一套；
- 后端 `secs-protocol` 里留着一份零调用方的 SML 实现，`Main` 留在 `secs-cli` 之外的装配层；
- `RuntimeContext`（1283 行）同时做门面、会话 API 落地、主机注册表与压测引擎；
- 前端 `api.ts`（1316 行）、`state/useBackend.ts`（1027 行）、`styles/panels.css`（2178 行）
  都是"什么都在里面"的单文件，改一处要通读全篇。

结论：能跑不等于好维护。一次性的结构收敛比继续往上堆更划算。

## 决策

### 1. 仓库分区

| 位置 | 内容 |
|---|---|
| `docs/guides` `docs/reports` `docs/examples` `docs/adr` `docs/reference` | 文档按"活文档 / 归档 / 素材 / 决策 / 参考"分区，SML 与配置样例从 `templates/` 迁入 `docs/examples` |
| `tools/host-e2e` | 现场排障脚本（原先散在 `scripts/host-e2e`，含编译产物） |
| `build/installer` `build/run` `build/native` | 所有构建产物收敛到 `build/`，不再往版本库根丢 |
| `frontend/` | 只用 npm；`pnpm-lock.yaml` 删除，脚本与文档统一 `npm` |

理由：仓库根是"人能一眼看懂的地图"。产物、脚本、素材各有其位，`git status` 才可信。

### 2. 后端分层

- **`Main` 迁入 `secs-cli`**：启动入口本来就只服务 CLI/原生镜像，放在 `runtime` 会让
  "装配层"同时承担进程入口。
- **`runtime/sml` 下沉到 `secs-app/app.sml`**：SML 解析/渲染只是 `Secs2 ↔ 文本`，
  没有会话状态；`secs-app` 新增 `secs-protocol` 依赖，方向仍是单向（协议层是叶子库）。
- **`hostsim` 包只放主机主动端**（`HostSimulator`）；设备侧的 S2 命令处理
  `HostSideMessages` 留在 `session` 并改名 `HostCommandHandler`——名字带 `HostSim`
  会让人以为它属于模拟主机。`HostSimRegistry` 是装配期簿记，留在 `runtime` 根。
- **`RuntimeContext` 拆成门面 + 三个协作者**：
  `RuntimeApi`（会话/目录/报文/审计等 API 落地）、`HostSimRegistry`（主机实例注册表）、
  `BurstRunner`（服务端压测引擎）。`RuntimeContext` 只留健康信息、`close()` 与委托，
  协作者都是 package-private，包外看不到内部结构。

### 3. 前端分层

- **`src/api.ts` → `src/api/`**：`types.ts`（契约形状）、`base.ts`（地址/超时/错误归一化）、
  `sessions.ts` `configs.ts` `messages.ts` `hostsim.ts`（四个域的端点实现）、
  `index.ts`（`createClient()` 合并并声明返回 `ApiClient`，少写一个方法就编译失败）。
  原 `src/api.ts` 保留为一行 `export *`，33 个既有导入点不动。
- **`src/` 按职责分层**：`app/`（入口、路由、外壳、布局）、`api/`、`features/`（页面/面板/图表/编辑器）、
  `shared/`（通用控件 `ui/`、纯函数 `lib/`）、`state/`、`styles/`。
- **`useBackend` 只做装配**：审计（`useAuditFeed`）、运行时编辑与预览（`useDeviceEdits`）、
  设备现场操作（`useDeviceOps`）、服务端聚合与压测（`useServerOps`）、日志级别（`useLogLevel`）
  各自成 hook；`useBackend` 保留**唯一一个**轮询调度器、当前会话选择与对象装配。
  各域 hook 都 `useMemo` 返回稳定对象，`BackendController` 的引用语义不变。
- **`panels.css` 按主题切到 `styles/panels/`**，`panels.css` 退化为 `@import` 汇总，
  导入顺序 = 原文件顺序，层叠结果不变。

## 后果

- 正面：新增一个端点只改一个域文件；改界面结构不用先读 1000 行；包边界能被 ArchUnit 与
  编译错误守住（`hostsim → session` 单向、`app → protocol` 单向）。
- 代价：导入路径变长（`features/pages/...`）；文件更多，搜索时更依赖命名约定。
- 约束：
  - `src/api.ts` 只许是 barrel，不再写实现；
  - `styles/panels.css` 只许是 `@import` 列表，新规则进 `styles/panels/*.css`；
  - 新增 `state/` 下的域 hook 必须返回 `useMemo` 稳定对象，否则 `useBackend` 的 memo 形同虚设；
  - 后端 `runtime/hostsim` 不得反向依赖 `runtime` 根包的 package-private 类型。

## 验证

- 后端：`mvn -B verify` 581 个测试全绿；`mvn -B -Pnative verify` 构建成功，
  `NativeRuntimeIT` 2 条通过。
- 前端：`tsc --noEmit` 无错误；Vitest 17 个文件 119 条通过；Playwright 36 条通过
  （截图回归 37 条按设计跳过）；`vite build` 产出 CSS 68.16 kB / JS 384.56 kB。
- 详见 [重构验收报告](../reports/refactor-2026-11-09.md)。
