package com.shimizukenta.secssim.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * CLI 层的反射守门（UP-05）。
 *
 * <p>这条规则原先写在 secs-runtime 的 ArchitectureTest 里，但 cli 不在 runtime
 * 的 classpath 上，规则恒空——门禁看着绿，实际什么都没查。挪到所属模块后，
 * 哨兵同时断言类数，避免再次空转。</p>
 */
class CliArchitectureTest {

    private static final JavaClasses CLI =
            new ClassFileImporter().importPackages("com.shimizukenta.secssim.cli..");

    @Test
    void cliClassesAreImported() {
        assertTrue(CLI.size() >= 3, "cli 包类数异常偏少（" + CLI.size() + "）：导入器可能失效");
    }

    @Test
    void cliNeverUsesReflection() {
        noClasses()
                .that().resideInAPackage("com.shimizukenta.secssim.cli..")
                .should().dependOnClassesThat().resideInAPackage("java.lang.reflect..")
                .check(CLI);
    }
}
