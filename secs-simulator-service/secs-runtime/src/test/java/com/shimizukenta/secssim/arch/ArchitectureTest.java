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

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.shimizukenta");

    @Test
    void coreNeverDependsOnOuterLayers() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.core..")
                .should().dependOnClassesThat().resideInAnyPackage(OUTER_LAYERS)
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void coreNeverDependsOnJsonOrProtocol() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.shimizukenta.jsonhub..", "com.shimizukenta.secs..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void onlyHttpAdapterTouchesJdkHttpServer() {
        noClasses()
                .that().resideOutsideOfPackage("com.shimizukenta.secssim.api.http..")
                .should().dependOnClassesThat().resideInAPackage("com.sun.net.httpserver..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void protocolLayerOnlyDependsOnJdk() {
        classes()
                .that().resideInAPackage("com.shimizukenta.secs..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "com.shimizukenta.secs..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void jsonLayerOnlyDependsOnJdk() {
        classes()
                .that().resideInAPackage("com.shimizukenta.jsonhub..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "com.shimizukenta.jsonhub..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void newCodeNeverUsesReflection() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.shimizukenta.secssim.core..",
                        "com.shimizukenta.secssim.app..",
                        "com.shimizukenta.secssim.api..",
                        "com.shimizukenta.secssim.runtime..",
                        "com.shimizukenta.secssim.cli..")
                .should().dependOnClassesThat().resideInAPackage("java.lang.reflect..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }
}
