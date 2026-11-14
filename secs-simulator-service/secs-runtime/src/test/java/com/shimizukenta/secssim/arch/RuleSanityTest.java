package com.shimizukenta.secssim.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 证明架构规则本身有效，而不是"空跑也绿"。
 *
 * <p>这类自证测试极易被忽略：一旦 ArchUnit 因 class 文件版本等原因静默跳过全部类，
 * 所有规则都会恒真，守门人形同虚设。历史上正是如此——ArchUnit 1.3.0 的 ASM 读不了
 * JDK 25 的 major 69，{@code importPackages} 返回空集合却<b>不报错</b>。
 * 因此这里必须显式断言导入非空。
 * </p>
 */
class RuleSanityTest {

    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("fixtures");

    private static final JavaClasses MAIN =
            new ClassFileImporter().importPackages("com.shimizukenta");

    @Test
    void fixturesAreImported() {
        assertFalse(FIXTURES.isEmpty(), "fixtures 未被导入：架构规则会变成空跑");
    }

    @Test
    void mainClassesAreImported() {
        assertFalse(MAIN.isEmpty(), "主代码未被导入：架构规则会变成空跑");
    }

    @Test
    void violatingDependencyIsDetected() {
        assertThrows(AssertionError.class, () -> noClasses()
                .that().resideInAPackage("fixtures.core..")
                .should().dependOnClassesThat().resideInAPackage("fixtures.api..")
                .check(FIXTURES));
    }

    @Test
    void allowedDirectionPasses() {
        assertDoesNotThrow(() -> noClasses()
                .that().resideInAPackage("fixtures.api..")
                .should().dependOnClassesThat().resideInAPackage("fixtures.core..")
                .check(FIXTURES));
    }
}
