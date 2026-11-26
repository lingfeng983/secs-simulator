package com.shimizukenta.secssim.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 分层守门人。
 *
 * <p>这些规则的存在意义是：任何一次改动（人或 AI）只要越过分层边界，构建立即失败，
 * 而不是等到运行期出现"内核偷偷用了 HTTP"这种难以排查的耦合。
 * </p>
 */
class ArchitectureTest {

    private static final String[] OUTER_LAYERS = {
            "com.shimizukenta.secssim.app..",
            "com.shimizukenta.secssim.api..",
            "com.shimizukenta.secssim.runtime..",
            "com.shimizukenta.secssim.cli.."
    };

    /*
     * 只排除测试类，不排除 jar：mvn test 的依赖是 target/classes 目录，
     * mvn verify 的依赖是各模块 jar。排除 jar 会让 verify 下只导进 runtime
     * 自己的类，规则大面积空转、哨兵也误报（2026-11-26 native verify 实证）。
     * importPackages 已把范围限定在 com.shimizukenta，第三方 jar 不会混进来。
     */
    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.shimizukenta");

    /**
     * 哨兵：导入器必须真的看见类（评审 2026-11-24 F-ARCH-1，UP-05 扩包）。
     *
     * <p>ArchUnit 用 ASM 解析 class 文件；JDK 升级后旧 ASM 会静默跳过所有类，
     * 叠加各规则的 allowEmptyShould(true)，整套门禁会空转全绿。这里对每条规则
     * 涉及的包逐一设下限，让"门禁自己失效"变成显式失败。cli 不在本模块
     * classpath，由 secs-cli 模块的 CliArchitectureTest 单独守。</p>
     */
    static final java.util.Map<String, Integer> MIN_CLASSES = java.util.Map.of(
            "com.shimizukenta.secssim.core..", 50,
            "com.shimizukenta.secssim.app..", 30,
            "com.shimizukenta.secssim.api..", 30,
            "com.shimizukenta.secssim.runtime..", 100,
            "com.shimizukenta.secssim.store..", 20,
            "com.shimizukenta.secs..", 150,
            "com.shimizukenta.jsonhub..", 20);

    @Test
    void importerActuallySeesClasses() {
        org.junit.jupiter.api.Assertions.assertTrue(MAIN_CLASSES.size() > 300,
                "导入类数异常偏少（" + MAIN_CLASSES.size() + "）：ASM 版本可能与 JDK 不匹配，分层规则正在空转");
        MIN_CLASSES.forEach((pkg, min) -> org.junit.jupiter.api.Assertions.assertTrue(
                MAIN_CLASSES.that(com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(pkg))
                        .size() >= min,
                "关键包类数异常偏少: " + pkg + "（当前 "
                        + MAIN_CLASSES.that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                .resideInAPackage(pkg)).size() + "，下限 " + min + "）"));
    }

    /** app 不得反向依赖装配层/适配器/存储（此前只写在 POM 注释里，评审 2026-11-24 补门禁）。 */
    @Test
    void appNeverDependsOnOuterLayersOrStore() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.app..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.shimizukenta.secssim.api..",
                        "com.shimizukenta.secssim.runtime..",
                        "com.shimizukenta.secssim.cli..",
                        "com.shimizukenta.secssim.store..")
                .check(MAIN_CLASSES);
    }

    /** api-http 只认 app 的接口，不得直接依赖装配层与存储。 */
    @Test
    void apiNeverDependsOnRuntimeOrStore() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.shimizukenta.secssim.runtime..",
                        "com.shimizukenta.secssim.cli..",
                        "com.shimizukenta.secssim.store..")
                .check(MAIN_CLASSES);
    }

    /** store 只认识 json + JDBC（3.0 设计 §2.1），不得 import 任何 secssim 上层。 */
    @Test
    void storeNeverDependsOnUpperLayers() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.store..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.shimizukenta.secssim.core..",
                        "com.shimizukenta.secssim.app..",
                        "com.shimizukenta.secssim.api..",
                        "com.shimizukenta.secssim.runtime..",
                        "com.shimizukenta.secssim.cli..")
                .check(MAIN_CLASSES);
    }

    /** hostsim 子包不得反向依赖 runtime 根包（ADR-0016；此前仅靠 package-private 兜底）。 */
    @Test
    void hostsimNeverDependsOnRuntimeRoot() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.runtime.hostsim..")
                .should().dependOnClassesThat().resideInAPackage("com.shimizukenta.secssim.runtime")
                .check(MAIN_CLASSES);
    }

    @Test
    void coreNeverDependsOnOuterLayers() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.core..")
                .should().dependOnClassesThat().resideInAnyPackage(OUTER_LAYERS)
                .check(MAIN_CLASSES);
    }

    @Test
    void coreNeverDependsOnJsonOrProtocol() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.shimizukenta.jsonhub..", "com.shimizukenta.secs..")
                .check(MAIN_CLASSES);
    }

    @Test
    void onlyHttpAdapterTouchesJdkHttpServer() {
        noClasses()
                .that().resideOutsideOfPackage("com.shimizukenta.secssim.api.http..")
                .should().dependOnClassesThat().resideInAPackage("com.sun.net.httpserver..")
                .check(MAIN_CLASSES);
    }

    @Test
    void protocolLayerOnlyDependsOnJdk() {
        classes()
                .that().resideInAPackage("com.shimizukenta.secs..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "com.shimizukenta.secs..")
                .check(MAIN_CLASSES);
    }

    @Test
    void jsonLayerOnlyDependsOnJdk() {
        classes()
                .that().resideInAPackage("com.shimizukenta.jsonhub..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "com.shimizukenta.jsonhub..")
                .check(MAIN_CLASSES);
    }

    /**
     * 新代码不用反射（java.lang.reflect）。
     *
     * <p>口径说明（评审 2026-11-24 P3）：store 的 {@code Class.forName("org.sqlite.JDBC")}
     * 是 JDBC 驱动加载的惯用法，属条文豁免——本规则只查 java.lang.reflect 依赖，
     * 不覆盖 Class.forName；store 也不在名单内。若将来要收紧，应同时覆盖
     * java.lang.invoke 并为驱动加载写明豁免。cli 不在本模块 classpath，
     * 由 secs-cli 模块的 CliArchitectureTest 单独守，避免本规则恒空。</p>
     */
    @Test
    void newCodeNeverUsesReflection() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.shimizukenta.secssim.core..",
                        "com.shimizukenta.secssim.app..",
                        "com.shimizukenta.secssim.api..",
                        "com.shimizukenta.secssim.runtime..")
                .should().dependOnClassesThat().resideInAPackage("java.lang.reflect..")
                .check(MAIN_CLASSES);
    }
}
