# ADR-026: 「状态变更 → 生成下游单据」收回服务端(事件驱动 + 最终一致 + 对账兜底)

- **状态**: 已采纳(2026-09,源于 R-64)
- **上下文**: 领域不变式是「就诊单已确单 ⇒ 每条 LAB / MEDICATION 医嘱都被对应下游单据明细覆盖」。
  该不变式原先由**浏览器**维持:确单是 HTTP 1,生成检验申请 / 处方是另外的 HTTP 2、3。
  HTTP 1 已提交而 HTTP 2 失败时,无回滚、无重试、无对账,单据**永久缺失**。实测同一根因有三种面目:
  ① 请求被 Bean Validation 拦在审计切面之前 → **连审计都不留痕**;
  ② 第 2 个请求内部异常 → 整个事务回滚;
  ③ 判断依据是前端本地快照 `store.detail.orders`,快照 stale 就**静默跳过**。
  前端还把非 409 的错误吞成一句转瞬即逝的 warning toast,因此长期无人发现。
  另有一个独立缺口:「确单后追加检验医嘱」这条路径下,前端根本不会再发第二次请求 → 检验科永远看不到。
- **决策**:
  1. **发布方为 `clinical`**,事件放它的 `domain`:`VisitOrdersConfirmedEvent`(record,自包含快照 ——
     只带 `visitId` / `patientId` / `doctorId` 与按 LAB / MEDICATION 分桶的医嘱 id,消费方无需回查发布方实体)。
     这与 ADR-016 的 `AppointmentCreatedEvent` 保持同一范式,`clinical` 因此不必依赖 `lab` / `pharmacy`(ADR-003 模块隔离红线)。
  2. **两个触发点、同一语义**,都由 `VisitService` 发布:`confirm()` 批量(当时所有 `status=CREATED` 的医嘱)、
     `addOrder()` 单条(就诊非草稿态时追加的医嘱即刻锁定)。两桶皆空不发。事件带 `Trigger` 枚举,
     使审计能回答"同一张就诊单为什么会有两张申请"。
  3. **消费方在各自模块的 `application`**:`VisitConfirmedLabListener` / `VisitConfirmedPrescriptionListener`,
     `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` + `@Transactional(REQUIRES_NEW)`,
     并**刻意吞掉异常**(只 log.error)。
  4. **对账安全网为必选项**:`DownstreamDocReconcileJob`(`app.reconcile.cron`,默认每 10 分钟)按 LEFT JOIN
     找出「已确单但医嘱未被下游单据覆盖」的就诊单逐单补建,每单独立事务。委托各模块自己的
     `findVisitIdsNeedingReconcile` + `createFromVisit`,任务类本身不碰实体/表。正常每轮补建 0 单,一旦补建即 WARN。
  5. **幂等语义改为"无事可做"而非"报错"**:`createFromVisit` 在「已有 PENDING 单据且无新医嘱」时由抛异常
     改为**返回既有单据**。事件重投与对账场景下这是正常路径,抛异常只会制造假错误,
     也让前端不得不靠"是不是 409"猜语义。
  6. **审计显式补写**:生成不再经 Controller ⇒ `@AuditLog` 切面拦不到。故把切面的写入逻辑抽为公共组件
     `platform.infrastructure.AuditRecorder`,由监听器与对账任务在**生成成功之后**调用
     (避免"审计说建了、库里没有")。动作名沿用 `CREATE_REQUISITION` / `CREATE_PRESCRIPTION`,既有审计查询口径不变;
     对账补建的 actor 为 `system`,与人工可区分。
  7. **前端删除编排步骤**:`VisitDetailView.vue` 去掉确单/追加后的 4 处第二次调用,确单回归纯状态变更。
- **后果**:
  - **取舍:此处选最终一致,而非强一致。** 这与 ADR-001「就诊 / 医嘱 / 收费同一事务强一致」并不矛盾 ——
    那是同一模块内的写,本条是跨模块的下游派生。理由是**确单是临床主流程,不能被下游单据拖垮**:
    若把生成并入确单事务,检验申请的一个校验问题就会让医生确不了单。
  - 代价是"生成可能静默失败",所以第 4 条的对账兜底不是加分项而是**该取舍的必要组成部分**——
    去掉它,只是把失败从浏览器搬到服务端。
  - 监听器为同步 AFTER_COMMIT(非 `@Async`),故确单 HTTP 响应返回时生成已完成,
    前端不存在"确单后立刻打开检验申请页为空"的窗口。这也是选择同步而非异步的理由。
  - `AuditRecorder` 必须放 `platform.infrastructure` 而非 `platform.support` —— 它要访问 `platform.domain.AuditLog`,
    而 ADR-003 的分层规则只允许 api / application / infrastructure 访问 domain(放错层直接构建失败)。
  - 行为变化需知悉:`POST /api/lab/requisitions`、`/api/pharmacy/prescriptions` 在"无新医嘱"时由 409 改为
    **200 + 既有单据**;两个端点保留,仅作手工补建入口。
  - 同类已存在的第二个跨模块事件:`OrderUpdatedEvent`(医嘱改名后通知 lab / pharmacy 同步各自快照明细)。
- **验证**: `VisitConfirmDownstreamIntegrationTest`(4 例,**不带 `@Transactional`** —— 否则 AFTER_COMMIT 监听器
  根本不触发,测试会给出"绿"的假绿)、`VisitServiceDownstreamEventTest`(7 例,含草稿态与 EXAM 不发事件的反向守护)、
  两个监听器单测、`DownstreamDocReconcileJobTest`(含"单张失败不影响其它")。
  两条链路已于 2026-09-11 在本地真实环境端到端复验,含**对账任务在真实 10 分钟边界自行补建并留痕**。
- **相关**: ADR-001(模块化单体 / 强一致取舍边界)、ADR-003(ArchUnit 模块与分层红线)、ADR-016(事件自包含快照范式)、
  R-64 全文见 [`../review/2026-09-08-code-review-report.md`](../review/2026-09-08-code-review-report.md)。
