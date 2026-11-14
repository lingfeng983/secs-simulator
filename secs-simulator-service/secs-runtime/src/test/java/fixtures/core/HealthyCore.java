package fixtures.core;

/**
 * 测试夹具：不越界的实现，是 RuleSanityTest 的阴性对照。
 */
public final class HealthyCore {

    /**
     * 创建实例。
     */
    public HealthyCore() {
    }

    /**
     * 纯计算。
     *
     * @return 固定值
     */
    public int value() {
        return 1;
    }
}
