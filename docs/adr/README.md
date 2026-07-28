# 架构决策记录 (Architecture Decision Records)

本目录包含项目的所有架构决策记录。

## 索引

| 编号 | 标题 | 状态 |
|------|------|------|
| [ADR-001](001-modular-monolith.md) | 模块化单体而非微服务 | 已采纳 |
| [ADR-002](002-single-database.md) | 单一关系库 + 每模块独立 schema | 已采纳 |
| [ADR-003](003-archunit.md) | 模块边界由 ArchUnit 强制 | 已采纳 |
| [ADR-004](004-hl7-fhir.md) | 互操作层讲 HL7 v2 + FHIR R4 | 已采纳 |
| [ADR-005](005-docker-compose.md) | 本地运行 Docker Compose 起步 | 已采纳 |
| [ADR-006](006-unified-iam.md) | 统一 IAM: 自管 JWT | 已采纳 |
| [ADR-007](007-middleware-scope.md) | 单体阶段中间件范围 | 已采纳 |
| [ADR-008](008-log-storage.md) | 日志存储: Loki + PostgreSQL | 已采纳 |
| [ADR-009](009-monolith-vs-microservice.md) | 微服务 vs 模块化单体再确认 | 已采纳 |
| [ADR-010](010-hybrid-architecture.md) | 模块化单体核心 + 2 抽出服务 | 已采纳 |
| [ADR-011](011-vue3-frontend.md) | 前端采用 Vue 3 + TypeScript | 已采纳 |
| [ADR-012](012-jwt-rbac.md) | 自管 JWT + 七权分立 RBAC | 已采纳 |
| [ADR-013](013-pinia-layout.md) | Pinia 状态管理 + 中后台布局 | 已采纳 |
| [ADR-014](014-patient-booking.md) | C 端患者域与体检预约 | 已采纳 |
| [ADR-015](015-menu-permission.md) | 菜单级权限后端驱动 | 已采纳 |
| [ADR-016](016-dispatch-engine.md) | 排队分发引擎 | 已采纳 |
| [ADR-017](017-security-permitall.md) | 默认态 permitAll 安全配置 | 已采纳 |
| [ADR-018](018-amqp-default.md) | AMQP 事件桥接默认启用 | 已采纳 |
| [ADR-019](019-report-module.md) | 报告模块独立限界上下文 | 已采纳 |
| [ADR-020](020-current-user.md) | 当前用户身份解析约定 | 已采纳 |
| [ADR-021](021-order-edit-cancel.md) | 就诊医嘱可修改/取消 | 已采纳 |
| [ADR-022](022-prescription-lab-permission.md) | 处方与检验申请权限收口 | 已采纳 |
| [ADR-023](023-registration-queue.md) | 门诊挂号与分诊排队 | 已采纳 |
| [ADR-024](024-openapi-swagger.md) | OpenAPI/Swagger 文档 | 已采纳 |
| [ADR-025](025-unified-error-handling.md) | 前后端统一错误处理 | 已采纳 |
