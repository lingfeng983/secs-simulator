package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * {@code context} 段（晶圆属性）的读写（schema v7）。
 */
final class ContextSectionJson {

    private static final String ROOT = "$";

    private ContextSectionJson() {
    }

    /**
     * 解析 {@code context} 段（schema v7）：晶圆属性。
     *
     * @param root 配置根
     * @return 属性定义（顺序即界面顺序）
     */
    static List<ContextTable.Def> parse(JsonHub root) {
        if (!root.containsKey("context")) {
            return List.of();
        }
        String path = ROOT + ".context";
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(root, "context", ROOT), path);
        List<ContextTable.Def> defs = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + "[" + i + "]";
            JsonHub item = items.get(i);
            String id = JsonAccess.string(item, "id", itemPath);
            ValueType type = parseType(JsonAccess.optionalString(item, "type", itemPath).orElse("ASCII"),
                    itemPath + ".type");
            DataSource source = DataSourceJson.parse(JsonAccess.object(item, "source", itemPath),
                    itemPath + ".source");
            defs.add(new ContextTable.Def(id, type, source));
        }
        return defs;
    }

    private static ValueType parseType(String name, String path) {
        try {
            return ValueType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path, "未知数据类型: " + name);
        }
    }

    /**
     * 晶圆属性段。
     *
     * @param context 属性定义
     * @param b       构造器
     * @return JSON
     */
    static JsonHub toJson(List<ContextTable.Def> context, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>();
        for (ContextTable.Def def : context) {
            items.add(b.object(
                    b.pair("id", def.id()),
                    b.pair("type", def.type().name()),
                    b.pair("source", DataSourceJson.toJson(def.source(), b))));
        }
        return b.array(items);
    }
}