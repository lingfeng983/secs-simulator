package com.shimizukenta.secssim.app.config;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * 参数模板体：一段与具体数据项无关的「值定义」（schema v7）。
 *
 * <p>v6 的模板体是"曲线 + 组合 + 实例 + result"的迷你模板图，复制到设备时还要留一份
 * 实例绑定；v7 把它收成一个 {@code source}：</p>
 *
 * <pre>
 * { "source": { "mode": "curve", "clock": "phase", "curve": { "type": "sine", ... } } }
 * </pre>
 *
 * <p>于是"挂到数据项上"就是<b>深拷贝这一段 JSON</b>——没有 id 改写、没有绑定表，
 * 复制出来的配置与库里再无关系。多输出的组合也在这里被折叠成
 * {@code mode:"expr"} + 私有 {@code vars}：变量只在这个数据项内部可见，
 * 不产生任何中间 SVID，因此不需要"实例"这个概念。</p>
 */
public final class ParamTemplateBody {

    private ParamTemplateBody() {
    }

    /**
     * 解析模板体里的值定义。
     *
     * @param text 模板体 JSON 原文
     * @return 值定义
     * @throws ConfigException JSON 非法或缺少 source
     */
    public static DataSource parse(String text) {
        JsonHub body;
        try {
            body = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            throw new ConfigException("$", "不是合法的 JSON: " + e.getMessage());
        }
        if (!body.isObject()) {
            throw new ConfigException("$", "参数模板体应为 JSON 对象");
        }
        if (!body.containsKey("source")) {
            throw new ConfigException("$.source", "参数模板体需要 source（一段值定义）");
        }
        return DataSourceJson.parse(body.get("source"), "$.source");
    }

    /**
     * 归一化：校验并写成统一的 {@code {"source": …}} 形状。
     *
     * <p>入库统一走这里，前端拿到的永远是同一种形状。模板体只有 source 一个字段，
     * 多写的键一定是写错了，因此不做"未知键保留"。</p>
     *
     * @param text 模板体 JSON 原文
     * @return 归一化后的 JSON
     * @throws ConfigException 同 {@link #parse(String)}
     */
    public static String normalize(String text) {
        DataSource source = parse(text);
        JsonHubBuilder b = JsonHubBuilder.getInstance();
        return b.object(b.pair("source", DataSourceJson.toJson(source, b))).prettyPrint();
    }
}
