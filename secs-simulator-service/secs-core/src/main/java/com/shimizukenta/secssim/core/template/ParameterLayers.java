package com.shimizukenta.secssim.core.template;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 参数的 5 级覆盖链。
 *
 * <p>从低到高：模板默认值 → 单元模板 → 单元实例 → profile → 运行时手动。
 * 高优先级覆盖低优先级。之所以要分级，是因为这几类改动的人不同、频率不同：
 * 模板默认值由模板作者定，单元参数由工程配置，profile 随工艺切换，
 * 运行时手动只用于临时试验且不落盘。
 * </p>
 */
public final class ParameterLayers {

    /**
     * 覆盖层级，声明顺序即优先级（后者覆盖前者）。
     */
    public enum Layer {

        /** 模板自身的默认值。 */
        TEMPLATE_DEFAULT,

        /** 单元模板（一类腔体的标准参数）。 */
        UNIT_TEMPLATE,

        /** 具体单元实例。 */
        UNIT,

        /** 当前 profile（工艺切换）。 */
        PROFILE,

        /** 运行时手动覆盖，不落盘。 */
        RUNTIME
    }

    private static final ParameterLayers EMPTY = new ParameterLayers(Map.of());

    private final Map<Layer, Map<String, Double>> layers;

    private ParameterLayers(Map<Layer, Map<String, Double>> layers) {
        this.layers = layers;
    }

    /**
     * 空参数集。
     *
     * @return 空参数集
     */
    public static ParameterLayers empty() {
        return EMPTY;
    }

    /**
     * 追加一层（返回新实例，不修改自身）。
     *
     * @param layer  层级
     * @param values 参数
     * @return 新的参数集
     */
    public ParameterLayers with(Layer layer, Map<String, Double> values) {
        Map<Layer, Map<String, Double>> copy = new EnumMap<>(Layer.class);
        copy.putAll(this.layers);
        copy.put(layer, Map.copyOf(values));

        return new ParameterLayers(Map.copyOf(copy));
    }

    /** 枚举数组每次 values() 都会克隆一份；resolve 在每个求值点被调用，缓存成常量。 */
    private static final Layer[] LAYERS_BY_PRIORITY;
    static {
        Layer[] all = Layer.values();
        LAYERS_BY_PRIORITY = new Layer[all.length];
        for (int i = 0; i < all.length; i++) {
            LAYERS_BY_PRIORITY[i] = all[all.length - 1 - i];
        }
    }

    /**
     * 解析参数：从最高层往下找，第一个命中的生效。
     *
     * @param name 参数名
     * @return 值
     */
    public OptionalDouble resolve(String name) {
        Layer[] layersByPriority = LAYERS_BY_PRIORITY;

        for (int i = 0; i < layersByPriority.length; i++) {
            Map<String, Double> values = this.layers.get(layersByPriority[i]);
            if (values != null && values.containsKey(name)) {
                return OptionalDouble.of(values.get(name));
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * 合成后的有效参数（用于 UI 展示与排错）。
     *
     * @return 参数名 → 值
     */
    public Map<String, Double> effective() {
        Map<String, Double> merged = new LinkedHashMap<>();

        for (Layer layer : Layer.values()) {
            Map<String, Double> values = this.layers.get(layer);
            if (values != null) {
                merged.putAll(values);
            }
        }
        return Map.copyOf(merged);
    }

    /**
     * 某一层是否显式设置了参数（用于 UI 显示"这个值来自哪一级"）。
     *
     * @param layer 层级
     * @param name  参数名
     * @return 该层设置了返回 {@code true}
     */
    public boolean defines(Layer layer, String name) {
        Map<String, Double> values = this.layers.get(layer);
        return values != null && values.containsKey(name);
    }
}
