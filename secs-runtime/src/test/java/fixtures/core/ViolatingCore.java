package fixtures.core;

import fixtures.api.ApiThing;

/**
 * 测试夹具：故意从内核层反向依赖外层，用于证明架构规则会失败。
 */
public final class ViolatingCore {

    /**
     * 创建实例。
     */
    public ViolatingCore() {
    }

    /**
     * 反向依赖外层。
     *
     * @return 外层常量
     */
    public String leak() {
        return ApiThing.name();
    }
}
