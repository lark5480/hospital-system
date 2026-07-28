# ADR-016: 排队分发引擎（进程内事件驱动 + CQRS 读模型）

- **状态**: 已采纳
- **上下文**: 体检预约单生成后，需把各体检项分发到对应工位（station），并支持工位任务的状态推进（PENDING→IN_PROGRESS→DONE）与排队看板；这是 ADR-014 预约单的下游闭环。
- **决策**:
  1. `hospital-core` 新增 `dispatch` 包（沿用 DDD 分层）：`ExamTask`（写模型 `dispatch.exam_task`，工位任务 + 状态机）、`QueueBoard`（读模型 `dispatch.queue_board`，CQRS 物化投影，与写模型 1:1）、各自 Mapper、`DispatchService`、`DispatchController`（`GET /api/core/dispatch/board?station=`、`POST /{id}/start`、`POST /{id}/complete`，写操作需 STAFF 权限）。
  2. **事件驱动**：`AppointmentCreatedEvent` 做成**自包含快照**（`patientName` + 各 `ExamItemBrief`，含 `orderNo`/`station`/`durationMin`），由 `BookingService` 在预约提交后发布；下游零回查 booking/patient。`DispatchService` 用 `@TransactionalEventListener` 消费 → 生成各工位 `ExamTask` + `QueueBoard` 投影行；`start/complete` 推进状态并双向同步投影。
  3. **进程内 vs MQ**：采用 **Spring ApplicationEvent** 域内事件总线（非 RabbitMQ），因 dispatch 与 booking 同处 `hospital-core` 一个 JVM，域内事件正是 ADR-001/009 原则；RabbitMQ 是将来把 Dispatch 抽成独立服务时的桥接路径（strangler 式演进），现在上 MQ 属过早分布式。
  4. 前端 `DispatchView` 按 station 分列展示任务卡，支持「开始/完成」推进与工位筛选；菜单新增「排队看板」（STAFF 可见）。
- **后果**: 易 — 闭合"预约 → 分发 → 排队 → 看板"全链路，可在同一应用演示事件驱动 + CQRS + DDD，简历含金量高；事件自包含快照使未来抽服务天然防腐（booking→patient 仅经 `PatientService.getName` 公开读 API，守 ADR-003 模块边界）；难 — 当前 CQRS 是"写读同库双写"（lite），并非独立读库异步投影；真正的异步投影（抽服务时用 RabbitMQ 事件更新独立读库）留作后续演进点。
