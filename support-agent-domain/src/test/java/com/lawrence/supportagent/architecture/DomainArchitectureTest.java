package com.lawrence.supportagent.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 校验领域模块不依赖框架或适配器技术。 */
@AnalyzeClasses(packages = "com.lawrence.supportagent", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainArchitectureTest {
    /** 领域模块不得依赖 Spring、MyBatis 或 AgentScope。 */
    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "org.mybatis..", "io.agentscope..");
}
