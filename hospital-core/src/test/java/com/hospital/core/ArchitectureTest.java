package com.hospital.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.hospital.core.clinical.domain.Visit;

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

    /**
     * R-48 状态机收口守护:{@link com.hospital.core.clinical.domain.VisitStatus} 的类注释承诺
     * "杜绝各 Service 绕过守卫直接 setStatus 的旁路"——就诊状态必须经
     * {@link Visit#transitTo(com.hospital.core.clinical.domain.VisitStatus)} 迁移(内部校验合法转换),
     * 而非由各 Service 直接 {@code Visit.setStatus(String)}。
     *
     * <p>现状核实(src/main 全量 {@code setStatus(} 调用点):唯一的 Service→Visit.setStatus 出现在
     * {@code VisitService}。其中
     * <ul>
     *   <li>{@code VisitService.create} 与 {@code VisitService.createWithOrders} —— 在实体<b>初始构造期</b>
     *       将状态置为 {@code CREATED}(此刻实体尚无前序状态,状态机迁移规则不适用),属合法调用点;</li>
     *   <li>其余 {@code setStatus} 均落在 Order / Charge / ExamTask / QueueBoard / VisitReadModel / Registration 等
     *       其它实体上,不命中本规则 target。</li>
     * </ul>
     *
     * <p>白名单:按类名整体排除 {@code VisitService}(仅因上述两条 CREATED 初始化路径)。这是 ArchUnit 类级
     * {@code callMethod} 能表达的最细粒度排除;代价是 VisitService 内部新增的 setStatus 不会被本规则拦截,
     * 已在此显式记录。{@code VisitService} 之外的任何 {@code *Service} 直接调用 {@code Visit.setStatus} 都会构建失败。
     */
    @ArchTest
    static final ArchRule servicesMustNotBypassVisitStateMachine =
            noClasses()
                    .that().haveSimpleNameEndingWith("Service")
                    .and().doNotHaveSimpleName("VisitService")
                    .should().callMethod(Visit.class, "setStatus", String.class)
                    .because("就诊状态必须经 Visit#transitTo 守卫迁移;Service 不得直接 setStatus 旁路");

    /**
     * R-48 规则生效守卫:确认 ArchUnit 确实分析到 {@code *Service} 类,
     * 保证上面的状态机旁路规则不是对"空集合"的假通过(ArchUnit 默认 allowEmptyShould=false:
     * 选择集为空即失败,是一种显式的"规则失活"告警)。
     * {@code beAssignableTo(Object.class)} 对任何类恒真,故本规则只会因"未匹配到 *Service 类"而失败。
     */
    @ArchTest
    static final ArchRule visitStateMachineGuardIsArmed =
            classes()
                    .that().haveSimpleNameEndingWith("Service")
                    .should().beAssignableTo(Object.class)
                    .because("守卫:必须分析到至少一个 *Service 类,否则状态机旁路规则形同虚设");
}
