# 架构决策记录 (Architecture Decision Records)

本目录包含项目的所有架构决策记录。

> **两套编号不要混**：本目录用 `ADR-NNN`（架构决策）；`R-NN` 是代码审查问题编号，定义与实施状态在
> [`../review/2026-09-08-code-review-report.md`](../review/2026-09-08-code-review-report.md)。
> `AGENTS.md` / `README.md` 引用 R-NN 时（如 R-62、R-65）指的就是那份报告。
>
> **决策被演进怎么办**：不改写原文，而在文件末尾追加 `## 修订（YYYY-MM）` 段说明现状 —— 参照
> ADR-014 / 020 / 016 / 017 的写法。若整条决策已被废止，则在「状态」行显式标注 **已被取代** 并指向现行条目
> （这类 ADR 保留价值是"当时为什么会走到这一步"，不是现状）。

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
| [ADR-012](012-jwt-rbac.md) | 自管 JWT + 七权分立 RBAC | 已采纳（决策 2/5/6 **已被取代**，见修订） |
| [ADR-013](013-pinia-layout.md) | Pinia 状态管理 + 中后台布局 | 已采纳（含 2026-09 修订：路由清单与文件链路已变） |
| [ADR-014](014-patient-booking.md) | C 端患者域与体检预约 | 已采纳（含 2026-08 修订） |
| [ADR-015](015-menu-permission.md) | 菜单级权限后端驱动 | 已采纳 |
| [ADR-016](016-dispatch-engine.md) | 排队分发引擎 | 已采纳（含 2026-09 修订：写操作集已扩至 6 个） |
| [ADR-017](017-security-permitall.md) | 默认态 permitAll 安全配置 | **已被取代**（决策 2 的 Pinia 顺序仍有效，见修订） |
| [ADR-018](018-amqp-default.md) | AMQP 事件桥接默认启用 | 已采纳（`iam` profile 启动项已作废，见修订） |
| [ADR-019](019-report-module.md) | 报告模块独立限界上下文 | 已采纳 |
| [ADR-020](020-current-user.md) | 当前用户身份解析约定 | 已采纳（含 2026-07 / 2026-08 修订） |
| [ADR-021](021-order-edit-cancel.md) | 就诊医嘱可修改/取消 | 已采纳（2026-09 修订："已收费不可改/取消"闸门已补齐） |
| [ADR-022](022-prescription-lab-permission.md) | 处方与检验申请权限收口 | 已采纳（生成方式已由 ADR-026 收回服务端） |
| [ADR-023](023-registration-queue.md) | 门诊挂号与分诊排队 | 已采纳 |
| [ADR-024](024-openapi-swagger.md) | OpenAPI/Swagger 文档 | 已采纳 |
| [ADR-025](025-unified-error-handling.md) | 前后端统一错误处理 | 已采纳 |
| [ADR-026](026-downstream-doc-events.md) | 下游单据生成收回服务端（事件驱动 + 最终一致 + 对账） | 已采纳（2026-09） |

