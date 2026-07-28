# ADR-023: 门诊挂号与分诊排队

- **状态**: 已采纳
- **上下文**: 当前系统缺少门诊挂号环节，患者无法挂号排队等候就诊，门诊大屏（OutpatientScreenView）也无法展示候诊队列。
- **决策**:
  1. `hospital-core` 新增 `clinical` 包下的 `Registration` 实体（`clinical.registration` 表，含 patientId/deptId/doctorId/queueNo/status/visitId）、`RegistrationService`、`RegistrationController`（`POST /api/core/registrations` 挂号、`POST /api/core/registrations/call-next` 叫号、`POST /api/core/registrations/{id}/cancel` 取消、`GET /api/core/registrations` 列表、`GET /api/core/registrations/active` 当前科室候诊队列）。
  2. 状态机：`WAITING`（候诊）→ `CALLED`（已叫号，关联就诊单）/ `CANCELLED`（取消）。
  3. 叫号流程：医生叫号 → 创建就诊单 → 关联 registration.visitId → 状态置为 CALLED。
  4. 前端 `RegistrationView`（挂号台）、`OutpatientScreenView`（门诊大屏，按科室展示候诊队列）。
  5. 事件驱动：叫号后发布 `PatientCalledEvent`，经 RabbitMQ 桥接发给 notification-service 广播。
- **后果**: 易 — 闭合"挂号 → 候诊 → 叫号 → 就诊"全链路，门诊大屏实时展示候诊队列；难 — 叫号与就诊单创建的关联需保证幂等（同一挂号单不可重复叫号）。
