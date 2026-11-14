# ADR 0018 · 内网工具全放行：监听 0.0.0.0、移除 Host/Origin 防护

- 日期：2026-11-14
- 状态：已接受
- 取代：[ADR 0001](0001-foundation.md) 的「API 只绑回环」表述；移除 2026-10 审计
  （[audit-2026-10-28](../reports/audit-2026-10-28.md) / [audit-2026-10-29](../reports/audit-2026-10-29.md)）
  加回的 Host 白名单与写请求 Origin 校验

## 背景

本项目定位是车间/实验室**内网工具**：模拟 SECS/GEM 设备供真实主机连入、供工程师在别的
机器上打开界面观察与操作。实际使用中：

- 真实主机往往在另一台机器上，HSMS 需要远程可达；工程师的界面也常开在另一台机器，
  跨域白名单（同源/Tauri/回环 dev server）反而成了每次部署都要解释的门槛。
- 没有账号体系（`AccessControl` 恒 local/ADMIN），浏览器侧的 Host 白名单与 Origin 写校验
  防的是"用户浏览器里的恶意页面"，与内网工具的实际威胁面不匹配，维护成本大于收益。

## 决策

1. **HTTP 与 HSMS 默认监听 `0.0.0.0`**：`--bind` 默认值从 `127.0.0.1` 改为 `0.0.0.0`；
   设备配置的 `session.bindHost` 缺省值与 probe 形态同步。显式配置仍可收窄
   （`--bind 127.0.0.1`、配置里写 `"bindHost": "127.0.0.1"`）。
2. **跨域全放行**：`withCors` 不再校验 Host 与 Origin——任意来源的请求照常处理
   （预检一律 204），响应统一带 `Access-Control-Allow-Origin: *`（预检放行头保留
   `If-Match` 并发保护）。
3. **删除配置面**：`secssim.allowedHosts` / `SECS_SIM_ALLOWED_HOSTS` /
   `secssim.corsOrigins` / `SECS_SIM_CORS_ORIGINS` 一并移除（不再有白名单可配）。

## 后果

- 局域网内任何机器都能读写本工具（设备控制、报文历史、审计）——这是**有意为之**；
  暴露范围请用防火墙/网段控制，需要加密时仍可 `--tls-keystore`。
- 跨机器部署界面/后端不再需要任何 CORS 配置；vite dev 代理仍保留（同源开发更顺手）。
- 审计记录、请求截止时间、慢请求分流等运行时机制不受影响。
