# M8-e 验收报告 · 曲线预览

## 交付

| 层 | 内容 |
|---|---|
| `secs-runtime` | 会话新增 `previewJson(from, to, buckets)`：按 SVID 分桶算 min/max/avg |
| `secs-app` | `SessionRuntime.previewJson(...)` 默认实现（不支持预览的会话返回空序列） |
| `secs-api-http` | `GET /api/v1/preview?session=..&from=..&to=..&buckets=..`（需 token） |
| `frontend` | 「曲线预览」区：点按钮算 60 秒 / 120 桶，每条 SVID 一个 Canvas（min/max 包络 + avg 线） |

## 为什么是分桶而不是逐点

400 个 SVID × 60 秒 × 20Hz = 48 万点，直接把浏览器和网络打爆。
分桶后每个 SVID 只有 120 个点（3 个数组），既能看趋势又保得住尖峰：

```
每个桶：min / max / avg
```

**min/max 是刻意保留的**：故障注入产生的单点尖峰如果只做平均，会被削平——
"预览里看不见、现场却报警"，那是预览在骗人。

## 为什么这一版用 JSON 而不是二进制

M3 设计里写的是 Float32 二进制（400 SVID × 4096 桶 ≈ 20MB）。这一版先走 JSON：

| 维度 | 现状 |
|---|---|
| 当前上限 | 32 个 SVID × 120~500 桶 ≈ 50–200 KB JSON |
| 何时切二进制 | 单次预览超过 ~2MB，或 SVID 数超过 ~150 |
| 预留 | `previewJson` 已经是独立方法，换成 `series.bin` 只需替换传输层 |

先做对、再做快：界面上先能用，二进制通道等真的撑不住再上。

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **154 个**（core 79 + app 16 + api-http 14 + runtime 12 + cli 33），全绿 |
| 前端 | 150 KB（新增预览区） |
| 原生镜像 | `secs-sim.exe` 13.0 MB，原生端到端 2 个用例 2.0 s |

### 用例（`SessionPreviewTest`）

| 用例 | 断言 |
|---|---|
| 60 秒 / 60 桶 | `buckets=60`、`bucketMs=1000`；10 条序列（两腔体 × 5 SVID）；每条 min/max/avg 都是 60 个点 |
| 值域 | 1001（温度）每桶 `min ≤ max` 且都落在 `0..400` |
| 桶数上限 | 传 100000 被夹到 500 |

## 已知边界

- **只支持曲线型会话**：模板 DAG 驱动的会话也能用（`ValueSource` 是统一的），
  但配置里还没有"每个 SVID 用哪条曲线"的表达（M6 起模板 DAG 仍由代码构造）。
- **预览用的是当前绑定**：改配置后需要重开会话才会反映到预览里。
- **前端没有缩放/游标**：Canvas 只画包络与均值线；要做交互（缩放、游标读数、多轴）
  再引入 uPlot。
