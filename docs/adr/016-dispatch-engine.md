# ADR-016: 排队分发引擎（进程内事件驱动 + CQRS 读模型）

- **状态**: 已采纳
- **上下文**: 体检预约单生成后，需把各体检项分发到对应工位（station），并支持工位任务的状态推进（PENDING→IN_PROGRESS→DONE）与排队看板；这是 ADR-014 预约单的下游闭环。
- **决策**:
  1. `hospital-core` 新增 `dispatch` 包（沿用 DDD 分层）：`ExamTask`（写模型 `dispatch.exam_task`，工位任务 + 状态机）、`QueueBoard`（读模型 `dispatch.queue_board`，CQRS 物化投影，与写模型 1:1）、各自 Mapper、`DispatchService`、`DispatchController`（`GET /api/core/dispatch/board?station=`、`POST /{id}/start`、`POST /{id}/complete`，写操作需 STAFF 权限）。
  2. **事件驱动**：`AppointmentCreatedEvent` 做成**自包含快照**（`patientName` + 各 `ExamItemBrief`，含 `orderNo`/`station`/`durationMin`），由 `BookingService` 在预约提交后发布；下游零回查 booking/patient。`DispatchService` 用 `@TransactionalEventListener` 消费 → 生成各工位 `ExamTask` + `QueueBoard` 投影行；`start/complete` 推进状态并双向同步投影。
  3. **进程内 vs MQ**：采用 **Spring ApplicationEvent** 域内事件总线（非 RabbitMQ），因 dispatch 与 booking 同处 `hospital-core` 一个 JVM，域内事件正是 ADR-001/009 原则；RabbitMQ 是将来把 Dispatch 抽成独立服务时的桥接路径（strangler 式演进），现在上 MQ 属过早分布式。
  4. 前端 `DispatchView` 按 station 分列展示任务卡，支持「开始/完成」推进与工位筛选；菜单新增「排队看板」（STAFF 可见）。
- **后果**: 易 — 闭合"预约 → 分发 → 排队 → 看板"全链路，可在同一应用演示事件驱动 + CQRS + DDD，简历含金量高；事件自包含快照使未来抽服务天然防腐（booking→patient 仅经 `PatientService.getName` 公开读 API，守 ADR-003 模块边界）；难 — 当前 CQRS 是"写读同库双写"（lite），并非独立读库异步投影；真正的异步投影（抽服务时用 RabbitMQ 事件更新独立读库）留作后续演进点。

## 修订（2026-09）：本 ADR 只记录了 `start` / `complete`，实际写操作集更大

原文决策 1 列出的 `DispatchController` 端点（`board` / `{id}/start` / `{id}/complete`）是最初形态，
此后补齐为**六个写操作**（路径前缀 `/api/core/dispatch`）：

| 端点 | 语义 |
|---|---|
| `POST /{id}/start`、`POST /{id}/complete` | 本 ADR 已记录 |
| `POST /call-next` | 自动叫下一位（单条 SQL 取 `MIN(seq)` + `NOT EXISTS` 前序未完，见 R-38） |
| `POST /tasks/{id}/reorder-tail` | 过号重排到队尾 |
| `POST /tasks/{id}/skip` | 跳过（可逆，配合下述 `requeue`） |
| `POST /tasks/{id}/requeue` | 把跳过/过号的任务重新排回队列 |

- **写操作权限口径统一**为 `hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')`
  —— 即除药师（`pharmacy:dispense`）外的全部员工岗位。矩阵见 [`business-flow.md`](../business-flow.md)「排队分发权限」。
- **双重护栏**（原文未记）：患者级单活跃（一次只在一科检查）+ 医生指定顺序（前序项目未完不叫后续）。
- **叫号链路**：`start/complete/call-next` 会发布 `PatientCalledEvent`，C 端「我的排队」以 10 秒轮询 + 叫号横幅接收；
  大屏侧另有 SSE `/api/core/dispatch/sse/subscribe`（R-13 加 60s 超时 + 15s 心跳 + 连接上限；
  R-14 使 `station` 过滤真正生效；R-34 改为短期 ticket 订阅；R-65 放行 ASYNC 派发）。
- **读端点**：`GET /board?station=`、`GET /stations`、`GET /my-queue`（C 端，需归属校验）。
- 上述细节属**现状清单**，流程语义以 `business-flow.md` 为准，本 ADR 只补"当初决策少算了什么"。
