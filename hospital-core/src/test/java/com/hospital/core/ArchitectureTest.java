package com.hospital.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 架构红线(ArchUnit):CI 中一旦违反即构建失败。
 * 目标:防止模块化单体退化为大泥球。
 */
@AnalyzeClasses(packages = "com.hospital.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * 分层规则:
     * <ul>
     *   <li>api 可以被 application 依赖(application 实现 api 接口是 DDD published-interface 模式)</li>
     *   <li>application 可以被 api 和 job(定时任务) 依赖</li>
     *   <li>domain 只能被 api、application、infrastructure 访问</li>
     *   <li>AuditLogAspect 是 AOP 横切关注点,需检查跨模块返回类型以构建审计目标——已知例外</li>
     * </ul>
     */
    @ArchTest
    static final ArchRule layered =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .withOptionalLayers(true)
                    .layer("api").definedBy("..api..")
                    .layer("application").definedBy("..application..")
                    .layer("domain").definedBy("..domain..")
                    .layer("infrastructure").definedBy("..infrastructure..")
                    .layer("job").definedBy("..job..")
                    .whereLayer("api").mayOnlyBeAccessedByLayers("application")
                    .whereLayer("application").mayOnlyBeAccessedByLayers("api", "job")
                    .whereLayer("domain").mayOnlyBeAccessedByLayers("api", "application", "infrastructure")
                    .ignoreDependency(
                            resideInAnyPackage("..platform.aspect..", "..platform.config.."),
                            resideInAnyPackage("..platform.domain..", "..booking.api..",
                                    "..clinical.application..", "..clinical.domain..",
                                    "..org.domain..", "..patient.domain..",
                                    "..iam.domain..", "..iam.application.."))
                    // FHIR module is a facade layer that converts domain objects to FHIR resources
                    // It needs to access patient/clinical/org domain and application directly for conversion
                    .ignoreDependency(
                            resideInAnyPackage("..fhir.."),
                            resideInAnyPackage("..patient.domain..", "..clinical.domain..",
                                    "..org.domain..", "..org.application..",
                                    "..patient.application..", "..clinical.application.."))
                    // FHIR module's internal dependencies (converter -> domain)
                    .ignoreDependency(
                            resideInAnyPackage("..fhir.converter.."),
                            resideInAnyPackage("..fhir.domain.."));

    /** 模块隔离:clinical 不得依赖其它业务模块的内部实现(platform 是共享内核,允许)。 */
    @ArchTest
    static final ArchRule clinicalMustNotDependOnOtherModules =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..core.clinical..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..core.pharmacy..",
                            "..core.lab..",
                            "..core.operation..",
                            "..core.integration..");
}
