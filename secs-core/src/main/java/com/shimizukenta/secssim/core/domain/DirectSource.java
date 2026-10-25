package com.shimizukenta.secssim.core.domain;

import java.util.Map;
import java.util.Objects;

import com.shimizukenta.secssim.core.curve.Curve;

/**
 * SVID 的直配值来源（schema v6）。
 *
 * <p>允许把曲线/模板直接写在 SVID 定义上，不必经过
 * {@code templates.instances} 三层模型。两种形态二选一：</p>
 * <ul>
 *   <li>{@code curve} 非空——内联曲线，编译期合成一条单曲线模板实例；</li>
 *   <li>{@code template} 非空——引用 templates 里的模板（曲线模板或组合模板），
 *       组合模板多输出时必须用 {@code output} 指定绑哪个输出。</li>
 * </ul>
 *
 * <p>与 {@code templates.instances} 对同一 SVID 同时出现会在编译期以
 * 单写者规则报错（由模板图统一把关）。</p>
 *
 * @param curve    内联曲线；与 {@code template} 互斥
 * @param template 引用的模板 id；与 {@code curve} 互斥
 * @param output   组合模板的输出名；曲线模板可不填（单输出）
 * @param params   模板参数（UNIT 层）；内联曲线没有参数
 */
public record DirectSource(Curve curve, String template, String output, Map<String, Double> params) {

    /**
     * 校验并创建。
     *
     * @param curve    内联曲线
     * @param template 模板 id
     * @param output   输出名
     * @param params   参数
     */
    public DirectSource {
        if (curve != null && template != null) {
            throw new IllegalArgumentException("curve 与 template 只能二选一");
        }
        if (curve == null && template == null) {
            throw new IllegalArgumentException("curve 与 template 必须给一个");
        }
        if (template == null && output != null) {
            throw new IllegalArgumentException("output 只能搭配 template 使用");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * 内联曲线形态。
     *
     * @param curve 曲线
     * @return 直配来源
     */
    public static DirectSource ofCurve(Curve curve) {
        return new DirectSource(Objects.requireNonNull(curve, "curve"), null, null, Map.of());
    }

    /**
     * 模板引用形态。
     *
     * @param template 模板 id
     * @param output   输出名（曲线模板可传 {@code null}）
     * @param params   参数
     * @return 直配来源
     */
    public static DirectSource ofTemplate(String template, String output, Map<String, Double> params) {
        return new DirectSource(null, Objects.requireNonNull(template, "template"), output, params);
    }
}
