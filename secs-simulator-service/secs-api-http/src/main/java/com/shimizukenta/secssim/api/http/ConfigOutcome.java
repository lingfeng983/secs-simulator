package com.shimizukenta.secssim.api.http;

/**
 * 配置读写的结果。
 *
 * <p>与 {@link SessionOutcome} 分开：配置"校验不过"和"会话不能重载"是两回事，
 * 而前者必须把<b>具体哪里错了</b>带回界面，光一个状态码没有用。
 * </p>
 */
public enum ConfigOutcome {

    /** 成功。 */
    OK,

    /** 没有这个文件，或文件名非法。 */
    NOT_FOUND,

    /** 内容解析不了或编译不过；{@link ApiContext#saveConfig} 不会落盘。 */
    INVALID,

    /** 已经有同名的文件了。 */
    CONFLICT,

    /** 读或写盘失败。 */
    FAILED
}
