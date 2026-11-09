# M4-b 验收报告 · 配置 JSON（Schema v4）

## 交付

| 组件 | 内容 |
|---|---|
| `SessionConfigJson` | Schema v4 的读取与写出：会话、ID 空间、单元/SVID/报告/CEID、全局项、事件编排、限流 |
| `ConfigException` | 带字段路径（`$.equipment.units[0].svids[0].type`） |
| `JsonAccess` | 带路径追踪的类型读取，缺字段/类型不符都给出准确位置 |
| `SessionConfig` | 会话参数 + 编排 + 限流；`compile()` 直接产出设备模型 |
| 运行时 | `--config <path>`：装载→编译→起会话（端口为 0 时回退到 `--hsms-port`） |

## 分层：语法错误 vs 语义错误

| 层 | 负责 | 表现 |
|---|---|---|
| 解析（本层） | 字段是否存在、类型是否正确、枚举值是否合法 | 立即失败，带字段路径 |
| 编译（`EquipmentCompiler`） | 重复 ID、段重叠、跨单元引用、槽位缺失 | 一次报全 14 类问题 |

两者分开的好处：改配置时不会"修好一个语法错才看到下一个语义错"。

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **103 个**（core 73 + app 10 + runtime 12 + cli 8），全绿 |
| 原生镜像 | `secs-sim.exe` **13.0 MB** |
| 原生 `--config` 冒烟 | 用一份两单元配置启动，`READY ... sessions=1`，进程正常起停 |
| 配置驱动 S1F3 | `ConfigDrivenSessionTest`：从 JSON 文件装载 → 编译 → 起会话 → S1F3 返回配置里的 `UNIT_ID` |

## 又踩到一个真实的坑

**UTF-8 BOM**。用 PowerShell 的 `Set-Content -Encoding utf8`（或记事本）写配置会带 BOM，
JSON 解析器不认识，报"解析失败"且错误指向第一个字符——用户完全无法自行定位。
已修：读取前统一剥掉 BOM，并加了一个专门的测试。
这类问题的共同特征是"工具链默认行为 vs 规范"，不实测根本发现不了。

## 还没做

- 模板 DAG 的 JSON（表达式与实例绑定）：目前 DAG 仍由代码构造，配置只覆盖设备与事件。
- 预览批算的统计与分桶。
- `--config` 的热重载。
