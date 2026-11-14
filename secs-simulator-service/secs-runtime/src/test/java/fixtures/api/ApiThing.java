package fixtures.api;

/**
 * 测试夹具：模拟外层 API 类型，供 RuleSanityTest 使用。
 */
public final class ApiThing {

    private ApiThing() {
    }

    /**
     * 返回一个常量。
     *
     * @return 常量名
     */
    public static String name() {
        return "api";
    }
}
