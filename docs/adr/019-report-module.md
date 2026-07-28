# ADR-019: 报告模块（Report）作为独立限界上下文

- **状态**: 已采纳
- **上下文**: 临床就诊闭环中，检验/检查结果需整合为结构化报告，支持 DRAFT → PUBLISHED 状态机，并纳入审计日志；报告与就诊同属 `hospital-core` 单体，不独立部署。
- **决策**:
  1. `hospital-core` 新增 `report` 包（DDD 分层）：`Report` 实体（`report.record` 表，含 type/title/content/status/DRAFT→PUBLISHED）、`ReportService`、`ReportController`（`GET/POST /api/reports`、`POST /{id}/publish`）。
  2. 写操作以 `@AuditLog` 拦截（CREATE_REPORT / PUBLISH_REPORT），落 `platform.audit_log`；Controller 无 `@PreAuthorize`（默认态开放，iam 态由 SecurityConfig 统一收口）。
  3. 不与 booking/dispatch 模块发生依赖；报告内容以 Markdown 存储，type 区分 LAB（检验报告）/EXAM（检查报告）/CLINIC（门诊病历）。
- **后果**: 易 — 报告结构与状态机清晰、审计完备、独立 schema 为未来抽服务或对接 FHIR 报告文档做准备；难 — 当前报告内容为纯文本 Markdown，未引入结构化报告模板引擎（留作后续）。
