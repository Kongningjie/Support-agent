package com.lawrence.supportagent.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 锁定六模块中最关键的框架和适配器依赖边界。 */
@AnalyzeClasses(packages = "com.lawrence.supportagent", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureTest {
    /** 基础设施模块不得依赖 AgentScope。 */
    @ArchTest
    static final ArchRule INFRASTRUCTURE_HAS_NO_AGENTSCOPE = noClasses()
            .that().resideInAnyPackage("..persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("io.agentscope..");

    /** AgentScope SDK 只能由明确的 Agent 适配包引用。 */
    @ArchTest
    static final ArchRule ONLY_AGENT_PACKAGE_USES_AGENTSCOPE = noClasses()
            .that().resideOutsideOfPackage("com.lawrence.supportagent.agent..")
            .should().dependOnClassesThat().resideInAnyPackage("io.agentscope..");

    /** 接口适配层不得绕过应用端口直接依赖持久化实现。 */
    @ArchTest
    static final ArchRule INTERFACES_DO_NOT_DEPEND_ON_PERSISTENCE = noClasses()
            .that().resideInAnyPackage("..observability..", "..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..persistence..");
}
