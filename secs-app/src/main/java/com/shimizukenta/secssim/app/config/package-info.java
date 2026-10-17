/**
 * 配置读写：Schema v4 的 JSON ↔ 领域模型。
 *
 * <p>原则：<b>错误必须能定位到字段</b>。解析失败时给出形如
 * `$.equipment.units[0].svids[1].type` 的路径，而不是一句"解析失败"。
 * 现场工程师改配置时，前者能直接跳到那一行。
 * </p>
 */
package com.shimizukenta.secssim.app.config;
