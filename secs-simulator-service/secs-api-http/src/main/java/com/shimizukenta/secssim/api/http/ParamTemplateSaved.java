package com.shimizukenta.secssim.api.http;

import java.util.List;

/**
 * 一次参数模板保存的结果：状态 + 落库后的 id。
 *
 * <p>新建时 id 由服务端生成（{@code pt_} 前缀），要回给前端做后续编辑/删除的键；
 * 失败时 id 为空、问题清单在 {@link #result()} 里。</p>
 *
 * @param result 保存结果（状态 + 问题清单）
 * @param id     落库后的模板 id；失败时为 {@code null}
 */
public record ParamTemplateSaved(ConfigResult result, String id) {

    /**
     * 固化字段。
     *
     * @param result 结果
     * @param id     落库后的 id
     */
    public ParamTemplateSaved {
        if (result == null) {
            result = ConfigResult.failed("保存失败");
        }
    }

    /**
     * 保存成功。
     *
     * @param id 落库后的模板 id
     * @return 成功结果
     */
    public static ParamTemplateSaved ok(String id) {
        return new ParamTemplateSaved(ConfigResult.ok(), id);
    }

    /**
     * 内容不合法；不会落盘。
     *
     * @param problems 问题清单
     * @return 非法结果
     */
    public static ParamTemplateSaved invalid(List<String> problems) {
        return new ParamTemplateSaved(ConfigResult.invalid(problems), null);
    }
}
