# 文档总览

按"是否仍在指导开发"分区，避免历史报告与活文档混在一起。

| 分区 | 内容 | 维护状态 |
|---|---|---|
| [`guides/`](guides/) | 使用手册、配置 Schema、值引擎规则、二进制预览格式、工作台重设计方案 | **活文档**，随代码同步更新 |
| [`adr/`](adr/) | 0001–0016：分层、ID 空间、纯函数曲线、模板 DAG、事件编排、API 角色、配置编辑器、报文日志、虚拟线程、CI、目录与分层收敛等决策 | 决策记录，已接受的不再修改 |
| [`reports/`](reports/) | 里程碑验收（M0–M9）、全量审计 / 代码评审、实机测试、未完成清单 | **归档**，只追加不修改 |
| [`examples/`](examples/) | `sml/`（39 个主机/设备侧 SML 报文样例）、`config-v5-example.json` | 示例素材，供手动发送与配置编写参考 |
| [`reference/`](reference/) | SEMI E4/E5/E30/E37 中文整理、项目对照表；`pdf/` 为付费标准原文（不入库） | 参考资料 |
| [`openapi.yaml`](openapi.yaml) | HTTP API 机器可读契约（OpenAPI 3.0.3），由 `scripts/check-openapi.py` 校验重复键 | 契约，改接口必须同步 |

## guides

- [使用手册](guides/user-guide.md) — 界面操作、会话管理、报文、曲线、报告
- [配置格式 Schema v5](guides/schema-v5.md) — 当前配置格式（设备 / 会话 / 模板 / 事件计划）
- [配置格式 Schema v4](guides/schema-v4.md) — 旧格式，仍兼容读取
- [SVID 值变化机制与规则](guides/svid-value-model.md) — 默认曲线 / 模板图 / 事件效果 / 钉值的优先级与边界
- [二进制曲线预览格式（SCSP v1）](guides/preview-binary.md) — `GET /api/v1/preview.bin` 的字节布局
- [设备工作台重设计方案](guides/redesign-device-workbench.md) — 事件驱动 SVID 与单设备全景页设计稿

## adr

决策清单见 [README 的 ADR 列表](../README.md#文档)。

## reports（归档）

**里程碑验收**

- [M0](reports/m0-report.md) · [M1](reports/m1-report.md) · [M2](reports/m2-report.md) · [M3](reports/m3-report.md)
- [M4](reports/m4-report.md) · [M4-b](reports/m4b-report.md) · [M5](reports/m5-report.md)
- [M6](reports/m6-report.md) · [M6-b](reports/m6b-report.md) · [M6-c](reports/m6c-report.md)
- [M7](reports/m7-report.md)
- [M8-a](reports/m8a-report.md) · [M8-b](reports/m8b-report.md) · [M8-c](reports/m8c-report.md) · [M8-d](reports/m8d-report.md) · [M8-e](reports/m8e-report.md)
- [M9](reports/m9-report.md) · [M9-b](reports/m9b-report.md)

**审计与评审**

- [全局功能审计 2026-10-25](reports/audit-2026-10-25.md)
- [全量代码审核 2026-10-28](reports/audit-2026-10-28.md)
- [全量代码审核 2026-10-29](reports/audit-2026-10-29.md)
- [全量代码评审 2026-10-17](reports/code-review-2026-10-17.md)
- [全量代码评审 2026-10-20（协议符合性专项）](reports/code-review-2026-10-20.md)
- [实机全量测试 2026-10-30](reports/host-e2e-2026-10-30.md)
- [结构收敛验收报告 2026-11-09](reports/refactor-2026-11-09.md) — 三阶段目录/分层治理的验收数字，决策见 [ADR 0016](adr/0016-layout-and-layer-consolidation.md)
- [2.0.0 发布说明](reports/release-notes-2.0.0.md) — 面向使用者的下载、安装、能力与升级说明

**待办清单**

- [未完成清单 2026-10-17](reports/unfinished-2026-10-17.md)
- [未完成清单 2026-10-24](reports/unfinished-2026-10-24.md)

## examples

- `examples/sml/equip/` — 设备侧报文样例（S1F1、S1F13、S5F1、S6F11、S10F1 等）
- `examples/sml/host/` — 主机侧报文样例（S1F3、S2F33、S2F35、S10F2 等）
- `examples/config-v5-example.json` — 一份可直接编译的完整配置示例

用于「手动发送 SML」或编写新设备配置时参考；运行时**不会**读取这些文件。

## reference

- [标准参考资料索引](reference/README.md) — E4 / E5 / E30 / E37 中文整理与项目对照
- `reference/pdf/` — SEMI 付费标准 PDF，因版权不入库（本地保留）
