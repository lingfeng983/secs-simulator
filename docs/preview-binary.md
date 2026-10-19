# 二进制曲线预览格式（SCSP v1）

`GET /api/v1/preview.bin?session=&from=&to=&buckets=` 返回
`application/octet-stream`，固定头 + 每条序列的 `min/max/avg` 双精度数组。
所有整数与浮点均为**小端**。

## 固定头（48 字节）

| 偏移 | 类型 | 说明 |
|---:|---|---|
| 0 | 4 × u8 | magic：`S C S P` |
| 4 | u16 | 格式版本，当前 `1` |
| 6 | u16 | flags，当前 `0` |
| 8 | i64 | `from`（毫秒） |
| 16 | i64 | `to`（毫秒） |
| 24 | u32 | `buckets` |
| 28 | u64 | `bucketMs` |
| 36 | u32 | `svidTotal`（会话中的 SVID 总数） |
| 40 | u32 | `seriesCount`（实际返回条数，最多 32） |
| 44 | u32 | 保留，必须为 0 |

`truncated = svidTotal > seriesCount`。

## 序列区

每条序列按 SVID 升序排列：

| 类型 | 说明 |
|---|---|
| i32 | `svid` |
| f64 × buckets | `min` |
| f64 × buckets | `max` |
| f64 × buckets | `avg` |

正文总长度：

```
48 + seriesCount × (4 + 3 × buckets × 8)
```

## 读取示例（Python）

```python
import struct
from pathlib import Path

data = Path("preview.bin").read_bytes()
magic, version, flags, start, end, buckets, bucket_ms, total, count, reserved = \
    struct.unpack_from("<4sHHqqIqIII", data, 0)
assert magic == b"SCSP" and version == 1

offset = 48
for _ in range(count):
    svid, = struct.unpack_from("<i", data, offset)
    offset += 4
    values = struct.unpack_from(f"<{buckets * 3}d", data, offset)
    offset += buckets * 3 * 8
    lo, hi, avg = values[:buckets], values[buckets:2*buckets], values[2*buckets:]
    print(svid, lo[0], hi[0], avg[0])
```

## 为什么不复用 JSON

JSON 版本仍然是默认格式，适合人看和小数据。二进制版给：

- 400+ SVID、500 桶时不想让浏览器解析大段 JSON；
- 需要把预览交给 Python/Excel/绘图工具做离线分析；
- 需要确认"界面上的包络"和"原始数值"是同一份数据。

两者都走同一个 `previewData` 计算路径，避免两份实现慢慢漂移。
