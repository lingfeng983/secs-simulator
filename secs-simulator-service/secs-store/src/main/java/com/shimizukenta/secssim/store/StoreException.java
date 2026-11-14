package com.shimizukenta.secssim.store;

/**
 * 存储层异常：SQL 失败、约束冲突、迁移自检不过都在这里统一抛出。
 *
 * <p>kind 供上层映射 HTTP 状态码（404 / 409 / 500），本模块不认识 API 层的类型
 * （设计 §2.1 的依赖纪律）。</p>
 */
public class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 异常类别：一般失败 / 不存在（→404）/ 冲突（→409）。 */
    public enum Kind { GENERAL, NOT_FOUND, CONFLICT }

    private final Kind kind;

    public StoreException(String message) {
        this(Kind.GENERAL, message);
    }

    public StoreException(String message, Throwable cause) {
        this(Kind.GENERAL, message, cause);
    }

    public StoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StoreException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** 异常类别。 */
    public Kind kind() {
        return this.kind;
    }

    /** 实体或文档不存在。 */
    public static StoreException notFound(String message) {
        return new StoreException(Kind.NOT_FOUND, message);
    }

    /** 名字冲突 / 乐观锁冲突（revision 不一致）。 */
    public static StoreException conflict(String message) {
        return new StoreException(Kind.CONFLICT, message);
    }
}
