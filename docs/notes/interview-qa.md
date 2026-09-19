# Hospital System 医院信息系统 — 知识笔记与面试 Q&A

> 基于项目实际代码实现整理，涵盖模块化单体、状态机、并发控制、CQRS、事件驱动、JWT RBAC、审计追踪、FHIR R4、结构化病历、检查调度、数据库设计、CI/CD 等核心知识点。
>
> ⚠️ **本文定位：学习 / 面试向的通俗问答，非权威规范。** 机制以代码为准；改代码前要守的铁律见 [`../../AGENTS.md`](../../AGENTS.md)；架构「为什么这么选」见 [`../adr/`](../adr/README.md)。此处内容与 ADR / `architecture-design.md` 有主题重叠属刻意（同一决策的问答视角），不要把它当第二份真相源。

---
GitHub:[lark5480/hospital-system: 医院信息系统 - 模块化单体 + 2 抽出服务 + API Gateway 的 Java 全栈学习项目](https://github.com/lark5480/hospital-system)
## 一、模块化单体架构

### 知识概述

Hospital System 采用 **DDD 四层分层**（`api` / `application` / `domain` / `infrastructure`）的模块化单体架构，所有业务模块（clinical、patient、booking、dispatch、pharmacy、lab、report、org、iam、fhir）打包在 `hospital-core` 单一 Maven 模块内。每个业务域模块严格遵循四层结构，如 `clinical/domain/Visit.java`（实体）、`clinical/application/VisitService.java`（服务）、`clinical/infrastructure/VisitMapper.java`（MyBatis-Plus Mapper）、`clinical/api/VisitController.java`（REST 控制器）。

`platform` 是共享内核——所有模块均可依赖，提供审计日志（`AuditLogAspect`）、安全（`JwtAuthFilter`）、配置（`GlobalExceptionHandler`）等横切关注点。PostgreSQL 16 按域独立 schema（9 个 schema：`platform`、`clinical`、`patient`、`booking`、`dispatch`、`pharmacy`、`lab`、`report`、`org`），同一实例，ORM 使用 MyBatis-Plus `@TableName("schema.table")`。未来可按 strangler fig 模式抽出微服务（notification-service 和 file-service 已先行抽出）。

### 面试 Q&A

**Q1: 为什么选择模块化单体而不是微服务？**

**A:** 单家医院+团队小+数据不出院约束下，微服务的分布式事务、N 套 CI/CD 成本远高于收益。模块化单体既有边界清晰的好处（ArchUnit 强制），又保留演进退路。放弃的是单模块独立伸缩（单医院峰值可预测，垂直+只读副本足够），获得的是极低运维负担、强一致性（单库事务）。[ADR-001](../adr/001-modular-monolith.md) 和 [ADR-009](../adr/009-monolith-vs-microservice.md) 详细记录了这一决策。

**Q2: ArchUnit 具体强制了哪些规则？**

**A:** `ArchitectureTest` 目前有 **4 条规则**：(1) **分层规则** `layered`——`api` ← `application` ← `domain` → `infrastructure`，`job` 层可依赖 `application`，已知例外：`platform.aspect`（AOP 横切关注点，需检查跨模块返回类型以构建审计目标）、`fhir`（门面层，需跨模块转换 patient/clinical/org 的 domain 和 application）；(2) **模块隔离** `clinicalMustNotDependOnOtherModules`——`clinical` 不得依赖 `pharmacy`、`lab`、`operation`、`integration`（`platform` 是共享内核，所有模块可依赖）；(3) **状态机旁路守护** `servicesMustNotBypassVisitStateMachine`——除 `VisitService` 外任何 `*Service` 不得直接调 `Visit.setStatus`（见 Q4）；(4) **守卫自检** `visitStateMachineGuardIsArmed`——确认上一条确实分析到了 `*Service` 类，防止选择集为空时规则"形同虚设地通过"。规则在 `mvn verify` 期间运行，违例即构建失败。

**Q3: 9 个 schema 都在同一个数据库实例，为什么不拆库？**

**A:** [ADR-002](../adr/002-single-database.md) 记录了决策：单库事务简单、备份单一。schema-per-domain 提供逻辑隔离（表名可重复、权限可按 schema 粒度控制），未来拆分微服务时可直接按 schema 拆库，无需数据迁移。跨模块查询走 CQRS 读模型（`visit_read_model`）或集成层，不破坏模块边界。

---

## 二、就诊状态机与强一致性事务

### 知识概述

就诊状态机定义了 4 个状态：`CREATED`（草稿）→ `CONFIRMED`（已确单）→ `IN_PROGRESS`（进行中）→ `FINISHED`（已完成）。所有状态变更收口到 `Visit.transitTo(VisitStatus)` 方法，内部由 `VisitStatus.assertTransitionTo()` 校验合法转换，非法转换抛 `IllegalStateException`。`VisitStatus` 枚举的 `allowedTransitions()` 定义每个状态的合法目标集合：`CREATED → {CONFIRMED}`，`CONFIRMED → {IN_PROGRESS, FINISHED}`，`IN_PROGRESS → {FINISHED}`，`FINISHED → {}`（终态）。

就诊-医嘱-收费在同一 `@Transactional` 内提交（`VisitService.createWithOrders()`），这是模块化单体相比微服务 Saga 的核心优势。

⚠️ **但"确单 → 生成检验申请 / 处方"是刻意采用最终一致的**（详见 Q33）：确单事务提交后由 `@TransactionalEventListener(AFTER_COMMIT)` 生成下游单据，
生成失败**不回滚确单**（临床主流程不能被下游拖垮），漏生成由 `DownstreamDocReconcileJob` 每 10 分钟对账补建。
所以准确的说法是：**写模型内部强一致，"状态变更 → 派生单据"最终一致** —— 这个边界要能讲清楚，否则容易被面试官追问穿。

### 面试 Q&A

**Q4: 如何防止非法状态转换？**

**A:** `VisitStatus` 枚举的 `allowedTransitions()` 返回当前状态允许转换到的目标状态集合。`Visit.transitTo(VisitStatus target)` 先调用 `VisitStatus.of(this.status).assertTransitionTo(target)` 校验，若目标不在合法集合内则抛 `IllegalStateException("非法就诊状态转换: " + this + " → " + target)`。所有状态变更必须走 `transitTo()`，不允许直接 `setStatus()`。

这条约定**不靠自觉**：ArchUnit 有一条规则禁止 `VisitService` 之外任何 `*Service` 调用 `Visit.setStatus`（白名单里只有 `VisitService` 内部两处**初始构造期**置 `CREATED` 的合法调用，因为那一刻实体还没有前序状态、状态机规则不适用）；
而且**另有一条"守卫规则是否真的生效"的自检**（ArchUnit 选择集为空即失败），避免某次重构后规则悄悄失效。
`VisitStatusTest` 还用参数化矩阵固化 `of()` 的边界行为（`of(null)` 静默降级为 `CREATED`，但 `of("")` / 未知值会抛异常 —— 这个不一致本身也是要留意的脏数据风险）。

**Q5: 确单的幂等性怎么保证？**

**A:** `VisitService.confirm(Long visitId)` 方法先检查状态：已是 `CONFIRMED` 直接返回 `getDetail(visitId)`（幂等），非 `CREATED` 状态则 `transitTo()` 会抛异常拒绝。确单时若尚未指定医生，默认归属当前操作医生（`resolveCurrentDoctorId()`）。

**Q6: 为什么创建处方会顺带调用 confirm()？有什么坑？**

**A:** 确单表示医生已确认医嘱进入收费环节。`confirm()` 仅允许从 `CREATED` 状态转换。

⚠️ **坑**：回诊追加药品时如果就诊已是 `IN_PROGRESS`，直接调 `confirm()` 会因 `IN_PROGRESS → CONFIRMED` 不在合法转换集合中而抛 `IllegalStateException`。修复方案：`addOrder()` 方法仅检查 `isTerminal()`（即 `FINISHED` 拒绝追加），`IN_PROGRESS` 状态直接建方，不重复确单。

**Q7: 收费（pay）为什么要求先确单？**

**A:** `VisitService.pay()` 前置校验：`CREATED` 状态就诊单不可结算，抛 `IllegalStateException("就诊单尚未确单,不可结算,请先由医生确单")`。收费完成后，已确单就诊单自动推进为 `IN_PROGRESS`（进入就诊执行阶段）。这保证了"医生确认 → 收费 → 执行"的业务流程。

---

## 三、号源并发控制

### 知识概述

`SlotMapper.incrementBooked` 实现单条条件 UPDATE：`UPDATE booking.slot SET booked = booked + 1 WHERE id = #{id} AND booked < capacity`。返回受影响行数 0 即号源已满，事务回滚，DB 层面杜绝超卖。无需乐观锁版本字段，无需应用层先查后改（存在竞态条件）。

`BookingService.book()` 流程：预检号源存在且未过期 → `incrementBooked` 原子占号 → INSERT 预约单 → 发布 `AppointmentCreatedEvent`。

### 面试 Q&A

**Q8: 为什么不用乐观锁版本字段？**

**A:** 乐观锁需额外 `version` 字段+重试逻辑。原子条件 UPDATE 直接在 SQL 层面保证，并发下最多一个事务成功（MySQL/PostgreSQL 行锁串行化，第一个成功后 `booked` 达到 `capacity`，后续 WHERE 不满足返回 `updated=0`）。代码更简洁，无重试开销。

**Q9: 与秒杀系统的 Redis Lua 预扣对比？**

**A:** 并发量级不同。医院号源每天几十到几百，单库事务足够；秒杀万级 QPS，需 Redis 原子操作+异步落库。选型取决于实际并发量，不是"技术越高级越好"。`SlotMapper.incrementBooked` 一条 SQL 搞定。

测试要分两层说，别混：
- `BookingServiceTest.book_concurrent_oneSucceeds` 是**单测（Mockito）**，它只是模拟"第二次 `incrementBooked` 返回 0"的分支，验证的是**应用层对"号源已满"的处理**，并不是真的并发；
- 真正验证"数据库行锁在并发下只放行一个事务"的是 `BookingConcurrencyTest` —— 多线程 + **真实 PG**，且**刻意不加 `@Transactional`**（加了所有线程会共享同一连接/事务，根本测不出并发），清理按自造数据精确删除。

---

## 四、CQRS-lite 读模型

### 知识概述

`clinical.visit_read_model` 物化投影表，存储患者/医生/科室名称、订单数、总金额、付费状态等预计算字段。`VisitReadModelService.refresh()` 在每次写操作后同步刷新，与写模型同事务提交，强一致，不存在不一致窗口。分页列表从 O(N²) 内存分页改为 O(1) DB 分页（`LIMIT/OFFSET`），查询直接走 `visit_read_model` 单表。

`VisitReadModel` 实体标注 `@TableName("clinical.visit_read_model")`，包含物化字段：`patientName`、`doctorName`、`deptName`、`orderCount`、`totalAmount`、`payStatus`（`NO_CHARGES` / `HAS_UNPAID` / `ALL_PAID`）、`unpaidCount`。

后续优化（性能审查驱动）：刷新时由**2 次全量 `selectList` 改为 1 条聚合 SQL**，名称解析走 `NameCache`，写操作内复用本次刷新结果避免重复 `getDetail()`（R-16）；
启动期的读模型重建加了 `platform.meta` **水位表（版本守卫）** + 主键游标分批，启动步进总耗时降到 **36ms**（R-15）。
另有一个"主动放弃"的决策：读模型刷新**没有**改成异步 —— 异步会破坏集成测试里的事务可见性，收益不抵代价，已在 javadoc 写明。

### 面试 Q&A

**Q10: 为什么不直接用缓存？**

**A:** 就诊列表需要多条件过滤（主诉、科室、付费状态）+分页+关联查询（患者名、医生名、科室名），缓存解决不了 N+1 和内存分页问题。CQRS 读模型是预计算的宽表，写操作时由 `VisitReadModelService.refresh()` 一次性计算好所有聚合字段（`orderCount`、`totalAmount`、`payStatus` 等），读查询直接查单表，无需 JOIN。

**Q11: 读模型和写模型不一致怎么办？**

**A:** 同事务刷新，强一致，不存在不一致窗口。每次写操作（`createWithOrders`、`confirm`、`addOrder`、`editOrder`、`cancelOrder`、`refundOrder`、`pay`、`finishVisit`）内部都调用 `readModelService.refresh(visitId)`。`refresh()` 方法从写模型重新计算所有聚合字段后 UPSERT 到读模型表。`VisitReadModelTest`（集成测试）验证了创建就诊后读模型数据一致性。

---

## 五、事件驱动架构

### 知识概述

进程内事件：Spring `ApplicationEventPublisher` + `@EventListener` / `@TransactionalEventListener`。跨进程事件：4 个 AmqpBridge 类通过 `@TransactionalEventListener` 监听事务提交后发送 RabbitMQ topic exchange，`notification-service` 4 个 Consumer 异步消费。

AmqpBridge 类：`VisitEventAmqpBridge`（就诊创建）、`VisitStatusEventAmqpBridge`（就诊状态变更）、`OrderCreatedEventAmqpBridge`（医嘱创建）、`PatientCalledEventAmqpBridge`（叫号通知）。Consumer 类：`NotificationConsumer`、`VisitStatusEventConsumer`、`OrderCreatedEventConsumer`、`PatientCalledEventConsumer`。

除"跨进程通知"外，还有一类**面向进程内下游生成**的事件：`VisitOrdersConfirmedEvent` → `VisitConfirmedLabListener` / `VisitConfirmedPrescriptionListener`
（确单 / 追加医嘱时生成检验申请与处方），以及兜底的 `DownstreamDocReconcileJob`。这是本项目最值得讲的一次架构修正，见 **Q33**。

### 面试 Q&A

**Q12: @TransactionalEventListener vs @EventListener？**

**A:** `@TransactionalEventListener` 在事务提交后执行（默认 `AFTER_COMMIT`），保证 MQ 消息与 DB 一致性——事务回滚则不发。`@EventListener` 在方法调用时立即执行，事务未提交，如果后续事务回滚，事件已经发出但数据已回滚，造成不一致。本项目 AmqpBridge 全部使用 `@TransactionalEventListener`，如 `VisitStatusEventAmqpBridge.onVisitStatusChanged()` 在事务提交后才 `rabbitTemplate.convertAndSend()`。

`ReportPdfListener` 更进一步使用 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)` + `@Async("reportPdfExecutor")`，确保报告已持久化再生成 PDF，且异步执行不阻塞主流程。

**Q13: 为什么需要 4 个 AmqpBridge 而不是直接发 MQ？**

**A:** 解耦。业务模块（`VisitService`、`DispatchService`）只发布 Spring 事件（`eventPublisher.publishEvent()`），不感知 MQ 存在。Bridge 类在 `infrastructure` 层监听事件并转发到 RabbitMQ。未来换消息中间件（如 RocketMQ、Kafka）只改 Bridge 层，业务代码零修改。这也是 [ADR-018](../adr/018-amqp-default.md)（AMQP 事件桥接默认启用）的设计意图。

**Q14: notification-service 的防腐层怎么做的？**

**A:** notification-service 不依赖 hospital-core 的 JAR，而是维护自己的事件模型副本（`notification.model.VisitStatusEvent`、`OrderCreatedEvent` 等 record 类），与 core 事件结构一致但类独立。RabbitMQ JSON 消息传递，下游自有模型反序列化。注释明确标注"防腐层思路：下游自有模型"。

---

## 六、JWT 认证与 7 权限 RBAC

### 知识概述

统一账号表 `platform.sys_user`（phone 唯一 + BCrypt 密码）+ `platform.sys_user_role`（user_id × role_code 多对多），一个账号可同时绑定医生+患者+管理员。七个 authority：`visit:entry`（录入）/ `visit:audit`（审核）/ `order:execute`（检查执行）/ `pharmacy:dispense`（发药）/ `charge:pay`（收费）/ `system:admin`（管理）/ `patient:booking`（体检预约）。

多角色权限取并集：登录时收集所有角色的 authorities 合并到 JWT。`@PreAuthorize("hasAuthority('...')")` 标注在 Controller 方法，`SecurityConfig` + `JwtAuthFilter` 统一收权。前端菜单由 `MenuService` 按当前用户 authorities 动态过滤 `platform.menu` + `platform.menu_authority` 表。

加固现状（都是审查后补的，面试常被追问）：
- **令牌 4 小时有效 + 改密 / 重置即吊销**（Redis 吊销标记 `TokenRevocationService`；Redis 不可用时默认 **fail-closed** 拒绝请求，而不是"打挂 Redis 就能让吊销失效"）。
- **生产未注入 `APP_JWT_SECRET` 直接 fail-fast 拒绝启动**，不允许用默认密钥"带病上线"。
- **登录失败计数 + 15 分钟锁定**（`LoginAttemptService`，超限返回 429），防爆破。
- **登录入参走 JSON body**，不让密码进 URL（见 Q40）。
- **Swagger 按 profile 收口**：非 prod 匿名放行便于演示，prod 下 `/swagger-ui/**`、`/v3/api-docs/**` 移出 `permitAll`。
- **SSE 与文件服务的边界**见 Q37 / Q38。

### 面试 Q&A

**Q15: 七权 RBAC 怎么设计的？**

**A:** 七个 authority 对应七个业务操作维度：`visit:entry`（就诊录入）、`visit:audit`（就诊审核）、`order:execute`（检查执行）、`pharmacy:dispense`（药房发药）、`charge:pay`（收费结算）、`system:admin`（系统管理）、`patient:booking`（C端体检预约）。权限入库在 `platform.role` + `platform.role_authority` 表，管理员可通过 `RoleController` 调整角色-权限映射。菜单权限基于 DB 配置过滤——`menu_authority` 表关联菜单与 authority，`MenuService` 按用户权限并集裁剪可见菜单。

**Q16: 多角色权限怎么合并？**

**A:** 取并集。`sys_user_role` 多对多，登录时 `AuthService.login()` 查询用户所有角色，收集每个角色关联的 authorities 合并到 JWT。例如一个账号同时是医生（`visit:entry`、`order:execute`）和患者（`patient:booking`），JWT 中 authorities 包含全部三个权限，菜单显示并集可见项。

---

## 七、审计追踪与输入校验

### 知识概述

`@AuditLog` 注解 + `AuditLogAspect` AOP 切面，当前覆盖 **44 处**动作（`CREATE_VISIT`、`PAY_CHARGE`、`BOOK_APPOINTMENT`、`EDIT_ORDER`、`CANCEL_ORDER`、`SUBMIT_RESULTS` 等）。`buildTarget()` 两级策略自动提取实体 ID：(1) 优先从 `ResponseEntity` body 按类型匹配（`Visit→visit_id`、`Department→dept_id` 等）；(2) 从方法参数 `Long` + `@AuditLog.action` 名推断（含"DEPT"→`dept_id`，含"STAFF"→`staff_id`）。

> 切面**只能拦 Controller**；不经 Controller 的关键动作（事件监听器、`@Scheduled` 任务）需显式调 `platform.infrastructure.AuditRecorder` 补写，见 Q18 与 Q34。

`GlobalExceptionHandler`（`@RestControllerAdvice`）统一返回标准 JSON `{timestamp, status, error, message}`，覆盖 400/401/403/404/409/500 六类异常。Jakarta Validation + `@Valid` 全栈输入校验。

### 面试 Q&A

**Q17: 审计日志 target 字段怎么自动提取？**

**A:** `AuditLogAspect.buildTarget()` 两级策略：(1) 优先从 `ResponseEntity` 返回值 body 按类型匹配——`Visit→visit_id=`、`VisitDetail→visit_id=`、`AppointmentDetail→appointment_id=`、`Department→dept_id=`、`Staff→staff_id=`、`Menu→menu_id=`、`Role→role_code=`；(2) 从方法参数提取第一个 `Long` 参数，按 `@AuditLog.action` 名推断实体类型——action 含"DEPT"→`dept_id=`，含"STAFF"→`staff_id=`，含"MENU"→`menu_id=`，含"ROLE"→`role_id=`，默认→`visit_id=`。两级都未命中则返回方法签名名。

**Q18: 审计日志会不会影响性能？写入失败会不会拖垮业务？**

**A:** 三个点要讲清楚（早期实现与现在已不同，别按"同步、同事务"答）：

1. **已经异步写**：`AuditLogAspect` 把 INSERT 提交到专用线程池（`auditLogExecutor`），不占业务线程的 IO 时间。
   但**绝不因异步而静默丢失** —— 执行器未装配、或提交被拒绝（线程池饱和 / 关闭）时**降级为同步写一次**；
   异步任务内部异常也只记 ERROR，不再抛回业务线程。
2. **审计失败绝不影响业务**：`insertSafely` 用 try/catch 兜住。另外切面用 `try/finally` 而非"成功才写"，
   所以**失败的操作也留痕**（越权探测、非法状态流转这类"失败的操作"往往才是最需要审计的），
   且原始业务异常原样抛出，不会被审计异常替换。
3. **它不再是"与业务同一事务"**：异步写入走独立连接，**不能**假设审计与业务原子提交；
   而且切面**只能拦 Controller** —— 不经 Controller 的关键动作（事件监听器、`@Scheduled` 任务）它根本拦不到，
   必须显式调 `platform.infrastructure.AuditRecorder` 补写（否则 R-64 改造后"生成检验申请"会从审计轨迹里消失），
   该组件与切面共用同一套语义，避免两条路径的失败行为不一致。

**Q19: GlobalExceptionHandler 覆盖了哪些异常类型？**

**A:** 6 类状态码：(1) **400**：`MethodArgumentNotValidException`（@Valid 校验失败）、`ConstraintViolationException`（@Validated 校验失败）、`HttpMessageNotReadableException`（JSON 解析失败）、`MethodArgumentTypeMismatchException`（路径参数类型错误）、`MissingServletRequestParameterException`（缺少必填参数）；(2) **401**：`BadCredentialsException`（认证失败）；(3) **403**：`AccessDeniedException`（权限不足）；(4) **404**：`NoHandlerFoundException`（无匹配 Handler）、`IllegalArgumentException`（业务层资源不存在）；(5) **409**：`IllegalStateException`（业务状态冲突，如未缴费拦截、处方已发药）；(6) **500**：`Exception`（兜底，所有未预期异常）。前端 `ErrorBoundary.vue` + `main.ts` 全局 errorHandler + Axios 拦截器统一提示。

---

## 八、FHIR R4 互操作门面

### 知识概述

只读 API，手写 JSON 转换，不引入 HAPI FHIR 等重型库。支持 4 个资源类型：`Patient`（`FhirPatientController`）、`Encounter`（`FhirEncounterController`）、`Condition`（`FhirConditionController`）、`CapabilityStatement`（`FhirMetadataController`）。Converter 模式：`PatientConverter`、`EncounterConverter`、`ConditionConverter` 将内部实体转换为 FHIR R4 标准 JSON 资源。

API 端点：`GET /fhir/Patient/{id}`、`GET /fhir/Encounter/{id}`、`GET /fhir/Encounter?patient={id}`、`GET /fhir/Condition/{id}`、`GET /fhir/metadata`。

### 面试 Q&A

**Q20: 为什么不用 HAPI FHIR？**

**A:** HAPI FHIR 是重型库，引入大量依赖（FHIR 核心库、验证器、序列化器等）。个人项目只需只读接口，手写转换足够且能深入理解 FHIR 资源结构（`FhirPatient`、`FhirEncounter`、`FhirCondition` 都是手写的 POJO，`@JsonProperty("resourceType")` 标注 FHIR 标准字段）。Converter 模式清晰：`EncounterConverter.toFhir(Visit)` 将内部 `Visit` 状态映射为 FHIR Encounter 的 `status`（`CREATED/CONFIRMED→planned`、`IN_PROGRESS→in-progress`、`FINISHED→finished`）。

**Q21: FHIR Facade 只读，怎么写数据？**

**A:** FHIR 是对外互操作标准接口（互联互通测评方向），内部写操作走业务 API（`/api/core/...`）。FHIR 只读保证外部系统（如区域卫生平台、其他医院 HIS）安全获取数据，不暴露内部写接口。`SecurityConfig` 中 `/fhir/**` 配置为 `permitAll()`，未来可加独立鉴权。

---

## 九、结构化电子病历

### 知识概述

`clinical.medical_record` 表，JSONB 存储体格检查（`physical_exam`）、辅助检查（`auxiliary_exam`）、诊断（`diagnosis`）等半结构化字段。自定义 `JsonbTypeHandler`（`@MappedJdbcTypes(JdbcType.OTHER)`）解决 MyBatis-Plus + PostgreSQL JSONB 兼容问题——使用 `PGobject` 设置 `type="jsonb"` 序列化/反序列化。`MedicalRecord` 实体通过 `@TableField(typeHandler = JsonbTypeHandler.class)` 标注 JSONB 字段。

支持 GIN 索引（`idx_mr_diagnosis ON clinical.medical_record USING GIN (diagnosis)`），实现 JSONB 内容的高效查询。病历状态：`DRAFT`（草稿）→ `FINAL`（定稿），定稿后不可修改。

### 面试 Q&A

**Q22: 为什么用 JSONB 而不是关联表？**

**A:** 病历中体格检查/诊断结构灵活（不同科室模板不同，如内科有血压/体温，眼科有视力/眼压），JSONB 存储半结构化数据，PostgreSQL 原生支持，无需引入 MongoDB。GIN 索引支持 JSONB 内容查询。`MedicalRecordTest` 集成测试验证了 JSONB 持久化：`physicalExam` 存入 `Map.of("temperature", "38.2°C", "pulse", "92次/分")`，读取后 `containsKey("temperature")` 通过。

**Q23: MyBatis-Plus 的 JSONB 兼容问题是什么？**

⚠️ **坑**：MyBatis-Plus 默认 `TypeHandler` 不识别 PostgreSQL JSONB 类型，直接写入会报 `ERROR: column "physical_exam" is of type jsonb but expression is of type character varying`。修复：自定义 `JsonbTypeHandler` 继承 `BaseTypeHandler<Object>`，`setNonNullParameter()` 中使用 `PGobject`（`pgObj.setType("jsonb"); pgObj.setValue(json)`），`getNullableResult()` 中使用 `ObjectMapper` 反序列化。`MedicalRecord` 实体标注 `@TableName(value = "clinical.medical_record", autoResultMap = true)` 启用自动类型映射。

---

## 十、检查调度引擎

### 知识概述

事件驱动：`AppointmentCreatedEvent` → `DispatchService.onAppointmentCreated()` 为套餐每个项目生成 `ExamTask`（状态 `PENDING`），同步生成 `QueueBoard` 看板投影。核心约束：患者级单活跃（同一患者一次只能在一个科室检查，`assertIsPatientActive()` 校验）；医生指定顺序（仅当该任务是患者当前最早待检项时才允许开始，`assertIsPatientNext()` 校验 seq）。

跳过可逆：`requeue()` 将 `SKIPPED → PENDING` 排至队尾（患者去而复返场景），已出报告则不允许重排。报告生成：`maybeGenerateReport()` 检查无活跃任务（无 `PENDING/IN_PROGRESS`）且至少一项 `DONE` 时自动出报告，标题标注"完成X/Y项"，正文列出未检项并提示补检。全部跳过不出报告。

### 面试 Q&A

**Q24: 为什么跳过要设计成可逆的？**

**A:** 真实场景患者去而复返（如排队太久去吃饭又回来），跳过是临时放弃非永久终止。`DispatchService.requeue()` 将 `SKIPPED` 任务恢复为 `PENDING` 并排至队尾（`setSeq(maxSeq + 1)`）。业务护栏：已出报告的预约（查真实报告记录，不靠任务状态推断）不允许重排，避免重复出报告。

**Q25: 报告生成条件为什么放宽？**

**A:** 部分跳过仍需出具阶段性报告。`maybeGenerateReport()` 触发条件从"全部 DONE"改为"无活跃任务（PENDING/IN_PROGRESS）且至少一项 DONE"。报告标题标注"完成X/Y项"，正文列出未检项（SKIPPED 的项目）并提示补检建议。全部跳过（零 DONE）不出报告。`skip()` 方法执行后立即调用 `maybeGenerateReport()`，因为跳过可能是该预约最后一个活跃任务。

**Q26: 患者级单活跃约束怎么实现？**

**A:** `DispatchService.start(taskId)` 在开始检查前调用 `assertIsPatientActive(patientId)`，检查该患者是否已有 `IN_PROGRESS` 状态的任务（不同 appointmentId），有则抛异常。`assertIsPatientNext(task)` 检查该任务的 seq 是否为该患者当前最小 PENDING seq，确保按医生指定顺序检查。

---

## 十一、数据库设计

### 知识概述

PostgreSQL 16，schema-per-domain 隔离（9 个 schema），同一实例。JSONB 半结构化存储（结构化病历的体格检查/辅助检查/诊断），GIN 索引加速 JSONB 查询。MyBatis-Plus `@TableName("schema.table")` 映射，`map-underscore-to-camel-case: true` 自动驼峰转换。

关键表设计：`booking.slot`（号源，`booked < capacity` 条件 UPDATE 防超卖）、`clinical.visit_read_model`（CQRS 读模型，`visit_id UNIQUE`）、`clinical.medical_record`（JSONB 病历，GIN 索引）、`dispatch.exam_task` + `dispatch.queue_board`（检查任务写模型 + 看板读模型）、`platform.meta`（启动期重建的水位表）。

索引：性能审查时按**实际查询**补了 47 条（读模型 / 排队看板 / 就诊列表 / 医嘱与收费的过滤列，以及 JSONB 的 GIN），
并把"全表扫描 + 内存过滤"的写法改成条件**下推 WHERE**（R-04 / R-05）。
`DataInitializer` 启动期的全量 `DELETE` 也已改为"探测 + 告警"，绝不静默删数据。

### 面试 Q&A

**Q27: 为什么选 PostgreSQL 而不是 MySQL？**

**A:** JSONB 原生支持（结构化病历，`physical_exam JSONB DEFAULT '{}'`、`diagnosis JSONB DEFAULT '[]'`），GIN 索引加速查询。schema-per-domain 逻辑隔离（MySQL 8.0 也支持 JSON 但查询能力弱，无 GIN 索引）。PostgreSQL 还支持分区表、FDW 做 BI，扩展性强。

**Q28: schema-per-domain 的优势？**

**A:** 逻辑隔离，模块间表名可重复（如每个 schema 都有 `id` 字段），权限可按 schema 粒度控制（未来给不同服务账号不同 schema 的读写权限），未来拆分微服务时可直接按 schema 拆库，无需数据迁移。ORM 层 `@TableName("schema.table")` 透明映射，业务代码无感知。

---

## 十二、CI/CD 与测试

### 知识概述

GitHub Actions 单一 Job：推送到 main/master 及 PR 触发，在 JDK 21（Temurin）上执行 `mvn -B clean verify`（含 ArchUnit 架构红线 + 全部测试）。CI 环境配 PostgreSQL 16 / Redis 7 / RabbitMQ 3 三个 service container（`ci.yml` 中 `services` 段），与本地 `docker-compose.yml` 对齐。

**hospital-core 46 个测试类、284 个用例**（另有 notification 24 / file-service 20 / gateway 6；前端 vitest 4 个 spec、19 个用例），
覆盖 clinical / patient / org / booking / dispatch / pharmacy / lab / iam / report / fhir / platform。单元测试（Mockito + AssertJ）为主，
集成测试（`@SpringBootTest`，如 `VisitReadModelTest`、`MedicalRecordTest`、`FhirApiTest`）只用于需要真库 / 真事务的场景。

**注意集成测试分两种**：能回滚的标 `@Transactional`（结束即回滚，天然不污染数据）；
而"验证事务提交后才触发的逻辑"（`@TransactionalEventListener`、并发抢号、独立事务的清理任务）**必须刻意不加** `@Transactional`——
套在测试事务里事件根本不会触发，测试会给出"绿"的假象，这类用例要自己用 `@AfterEach` 按自造数据精确清理（见 Q36）。

### 面试 Q&A

**Q29: 测试覆盖策略是什么？**

**A:** 核心业务模块必须有单元测试（状态机、并发控制、CRUD），集成测试仅用于验证 DB 交互（JSONB 持久化、CQRS 读模型、FHIR API）。不加载 Spring 上下文为默认选择——单元测试用 `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks`，启动快、隔离好。集成测试（`@SpringBootTest`）仅用于需要真实 DB 的场景（如 `MedicalRecordTest` 验证 JSONB 读写、`VisitReadModelTest` 验证读模型刷新）。

两条硬约束：(1) 集成测试**自造数据 + 自清理**，不依赖 `schema.sql` / `DataInitializer` 播下的种子数据（否则换个环境、或种子一改就挂）；
(2) 验证"事务提交后才触发"的逻辑**不能加** `@Transactional`（见 Q36）。前端也有测试了：vitest + jsdom + `@vue/test-utils`，
且 `vitest.config.ts` **复用 `vite.config.ts` 的插件** —— 否则"按需引入漏掉某个组件"这类问题在测试里永远暴露不出来。

**Q30: CI 为什么需要中间件 service container？**

**A:** `@SpringBootTest` 集成测试需要真实中间件：PostgreSQL 执行 `schema.sql` + `DataInitializer` 数据迁移，Redis 用于缓存，RabbitMQ 用于事件桥接。CI `ci.yml` 中配置了三个 service container（Postgres 16 / Redis 7 / RabbitMQ 3），health check 确保就绪后才跑测试。默认凭据与 `application.yml` 对齐。

---

## 十三、技术选型取舍

### 面试 Q&A

**Q31: 项目中做了哪些生产级改进？**

**A:** 按优先级排序：

| # | 改进 | 为什么做 |
|---|---|---|
| 1 | **ArchUnit 架构治理** | 模块化单体容易退化为大泥球，CI 强制分层+模块隔离 |
| 2 | **CQRS-lite 读模型** | 就诊列表 N+1 + 内存分页性能问题，预计算宽表解决 |
| 3 | **FHIR R4 Facade** | 医疗互联互通标准接口，手写转换深入理解资源结构 |
| 4 | **结构化电子病历（JSONB）** | 病历结构灵活，JSONB + GIN 索引兼顾灵活与查询 |
| 5 | **CI + 全套测试（后端 334 用例 + 前端 19 用例）** | GitHub Actions 零成本，ArchUnit + 单元测试 + 集成测试 |
| 6 | **事件驱动 + AmqpBridge** | 模块解耦，未来换 MQ 只改 Bridge 层 |
| 7 | **统一错误处理** | 前后端错误格式统一，不白屏不泄露堆栈 |
| 8 | **事件化下游单据 + 对账兜底** | 消除"没有补偿的客户端编排"，把不变式收回服务端（见 Q33 / Q34） |
| 9 | **文件服务收口 + 反向守护测试** | 关闭"匿名可枚举 + 下载全部患者报告"这条通路（见 Q38） |
| 10 | **审计异步化 + 不经 Controller 也能留痕** | 审计不拖慢业务，但也绝不因异步而静默丢失（见 Q18） |

**Q32: 为什么不做限流/熔断/可观测性？**

**A:** 个人学习项目无高并发场景（单医院峰值可预测），模块化单体无需保护微服务（单库事务、无级联故障）。核心判断是"每个技术选型的 why 能讲清楚"比"堆中间件数量"更重要。面试时能说出"为什么不做"比"我做了"更显架构师判断力。

但**不是完全没有防护**，只是没做"面向高并发的设施"：登录接口有失败计数 + 15 分钟锁定（`LoginAttemptService`，超限返回 429）——
这是最需要防爆破的入口；文件上传有类型 / 大小 / 禁止覆盖限制；外部传入的错误信息不回显（防信息泄露）。
缺的是全局 QPS 限流、熔断、分布式追踪这类东西，在单医院规模下收益为负。

⚠️ **注意**：如果并发量增长，号源控制可加 Redis 预扣（参考秒杀系统），读模型可拆独立读存储，AmqpBridge 已为 MQ 扩展留好接口。

---

## 十四、生产化加固（代码审查驱动）

一次按"安全 / 性能 / 测试质量"三方独立审查 + 交叉质询的方式自查，共梳理出 **64 条问题**（7 Critical / 24 High / 26 Medium / 7 Low；
其中 3 条是复核阶段新发现的漏项，原审查漏掉了它们），逐条修复并回写状态，报告在 `docs/review/`。
这一节挑最有讲头的几条 —— **面试里"我发现了什么、怎么权衡的"比"我用了什么框架"更值钱**。

### 面试 Q&A

**Q33: 「确单自动生成检验申请 / 处方」为什么从"前端编排"改成"服务端事件驱动"？**

**A:** 原实现里「确单」（HTTP 1）只改状态，**生成检验申请 / 处方是前端紧接着发的 HTTP 2、3** —— 这是典型的
**没有补偿的客户端编排（client-orchestrated saga）**：HTTP 1 已提交而 HTTP 2 失败时，没有回滚、没有重试、没有对账，单据**永久缺失**。
它在一次调试里以**三种不同面目**出现：① 前端据本地快照（`store.detail.orders`）判断"有没有检验医嘱"，快照 stale 就静默跳过，请求根本不发；
② 请求被 Bean Validation 拦在切面之前 → **连审计都不留痕**（实测：库里有 `CONFIRM_VISIT` + `CREATE_PRESCRIPTION`，唯独没有 `CREATE_REQUISITION`）；
③ 第 2 个请求内部异常导致**整个事务回滚**。

修复：`VisitOrdersConfirmedEvent`（自包含快照，医嘱 id 按 LAB / MEDICATION 分桶）+ 两个
`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 监听器，前端删掉 4 处第二次调用。
**两个触发点同一语义**：确单时批量、已确单后追加医嘱时单条（缺后者就会出现"确单后再追加检验医嘱 → 检验科永远看不到"）。

关键取舍：**选最终一致而非强一致** —— 监听器**刻意吞掉异常**（确单是临床主流程，不能被下游拖垮），代价是可能静默失败，所以必须有对账兜底（Q34）。
另外 `clinical` 不能直接注入 `lab` / `pharmacy` 的 service（ArchUnit 模块隔离红线），走事件正好也把这条约束满足了。

**Q34: 那"对账兜底"是怎么做的？为什么它是必须的？**

**A:** `DownstreamDocReconcileJob`（`app.reconcile.cron`，默认每 10 分钟）：用一条
`LEFT JOIN lab.result_item ... WHERE o.status='CREATED' AND v.status<>'CREATED' AND ri.id IS NULL` 直接查出
**"就诊已确单、但仍有医嘱未被下游单据覆盖"**的就诊单（即领域不变式的反例），逐单调既有幂等路径补建，每单独立事务、单张失败不影响其它。

**为什么必须要有**：把生成改成"事后异步 + 吞异常"之后，如果只有日志，失败就只是**从浏览器搬到了服务端的黑盒里** —— 单据依旧永久缺失，只是没人看见。
对账把"永久静默缺失"变成"短暂延迟后自愈"。可观测性上：正常每轮补建 0 单（DEBUG），一旦出现补建即 WARN；
**审计里也能看出来** —— 补建记录 actor = `system`、detail 标注"触发: 对账补建"，与人工确单触发（actor 是真实医生）可区分。

**Q35: 这套流程里的"幂等"体现在哪几层？**

**A:** 三层，缺一层就会被"重复触发"打穿：
1. `VisitService.confirm()` 对已 `CONFIRMED` 提前返回 —— 重复点确单不会重复触发下游事件；
2. 两个 `createFromVisit` 在"已有 PENDING 单据且无新医嘱"时**返回既有单据而不是抛异常** ——
   对事件重投 / 对账而言"没东西可追加"是正常的无事可做（原来靠抛异常让前端用"是不是 409"猜语义，这种约定很脆）；
3. 对账任务只处理"未被覆盖"的医嘱，重复执行安全。

**Q36: 为什么有些集成测试"故意不加 `@Transactional`"？**

**A:** 因为 `@TransactionalEventListener(AFTER_COMMIT)` **只在事务提交后触发**。测试方法上的 `@Transactional` 会让事务在用例结束**回滚、永不提交**
→ 监听器不触发 → 断言"单据已生成"必然失败。更危险的是另一种写法：不测事件链路、直接调 service 再断言，会得到**假绿**（测试通过但真实运行时是坏的）。
所以这类用例（确单生成下游单据、预约取消联动、并发抢号、独立事务的清理任务）刻意**不加** `@Transactional`，改用 `@AfterEach` 按自造数据精确清理。
还有一点：审计 / 事件的写入是**异步**的，断言要短暂轮询等待，直接断言会因竞态随机失败。

**Q37: SSE 用 `EventSource` 不能自定义请求头，鉴权怎么做？**

**A:** 先 `POST /api/core/sse/ticket`（Bearer）换取 **60 秒、`scope=sse`** 的短期 ticket，再以 `?ticket=` 订阅。
关键约束：`JwtAuthFilter` 显式**拒绝**把 ticket 当普通令牌使用（否则"URL 上的凭证"就能换全量 API 访问）。
前端必须**自行退避重连** —— 原生自动重连会复用已过期 ticket，必然失败。
notification-service 侧同样收紧：订阅既要 core 签发的 ticket，又要**任一员工权限**（纯患者角色 403），不再 `permitAll`。

**Q38: 文件服务的边界是怎么收的？（一个"自己审出来的洞"）**

**A:** 原设计是网关把 `/api/files/**` 直接转发到 file-service，并由网关注入"内部令牌"。问题在于：
① 网关默认 profile 就是 `permitAll`（仓库里根本没有 `application-iam.yml`），注入令牌**不区分调用者身份**；
② `list` 不传 `patientId` 会返回**全部**对象及各自的 `patientId`，而 `download` 的归属校验恰好依赖这组对应关系
→ 串起来就是**匿名可枚举 + 下载全部患者报告**（`list` 把校验需要的"钥匙"自己送了出去）。
修复：撤掉网关该路由，文件访问统一经 core 的 `/api/core/files` 代理（归属判定在 core —— 只有那里有身份上下文），
file-service 退为纯内网存储层；并加了**反向断言测试**（路由不得复活、令牌不得回流网关）防止有人加回来。

**Q39: Element Plus 按需引入踩了哪些坑？**

**A:** ① 不能连样式一起按需：主题映射层（`styles/element.css` 的 `--el-*`）依赖全量 CSS 的加载顺序，
且 `ElMessage` / `ElMessageBox` 是**显式 import 的函数式 API**、不走 resolver，删了全量 CSS 弹窗就会裸奔；
② 最隐蔽的是 `unplugin-vue-components` **只解析 `<el-xxx>` 标签，不解析 `v-loading` 这类指令** ——
必须显式 `app.use(ElLoading)`，否则全仓的 `v-loading` 静默失效（构建成功、类型检查通过，只有真跑页面才发现）。
教训：**"构建成功 ≠ 组件可用"**，所以补的是"真正挂载组件"的回归测试（vitest 复用 Vite 插件），而不是 grep 产物 ——
grep 也不可靠，Element Plus 的类名是运行时用命名空间拼的，产物里根本没有那个字符串。

**Q40: 还有哪些"看起来没事、其实会静默劣化"的坑？**

**A:** 都是审查里挖出来的同类问题（不报错、只是悄悄变差）：
- **token 存 localStorage** → 关掉浏览器仍留存、XSS 可长期读取；改 `sessionStorage`。
  附带一个更硬的反例：不能写 `const S = sessionStorage` —— 隐私模式 / 禁用站点数据时**在模块加载阶段就抛错，整站白屏**，必须先探测可用性、退化到内存。
- **PDF 中文字体路径写死 Windows** → CI / 容器渲染成方框且不报错；改为配置项 + 候选路径探测 + 降级告警。
- **启动期全量重建读模型** → 加水位表（版本守卫）+ 主键游标分批 + 无待迁移行提前短路，启动步进总耗时降到 **36ms**。
- **金额用浮点 / scale 漂移** → 统一 `BigDecimal` 两位小数 + 正数 / 上限校验。
- **N+1 与全表扫描**：读模型聚合 SQL 取代多次全量 `selectList`、`listExams` 批量 `selectBatchIds`、名称解析走缓存、索引下推 `WHERE`。
- **登录密码放在 URL query** → 会被 access log / 浏览器历史 / Referer / 网关注日志；改 JSON 请求体。
- **审计只记成功** → 改为 `try/finally`，失败的操作也留痕（越权探测、非法状态流转才是最该看的）。
- **启动期破坏性 DELETE** → 改为"探测 + 告警"，绝不静默删线上数据。
