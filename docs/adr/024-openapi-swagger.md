# ADR-024: OpenAPI/Swagger 文档（23 个 Controller 注解全覆盖）

- **状态**: 已采纳
- **上下文**: 项目需要标准化的 API 文档，便于前后端联调与作品集展示；手动维护文档易与实际接口脱节。
- **决策**:
  1. 引入 **springdoc-openapi-starter-webmvc-ui 2.5.0**，自动生成 OpenAPI 3.0 规范文档。
  2. 所有 23 个 Controller 添加 `@Tag`（分组）、`@Operation`（接口描述）、`@Parameter`（参数说明）注解，确保 Swagger UI 展示完整。
  3. 配置类：`platform/config/OpenApiConfig.java`，定义 API 标题、版本、描述等元信息。
  4. 访问地址：
     - 直接访问 hospital-core：`http://localhost:8101/swagger-ui.html`
     - 通过 API 网关：`http://localhost:8104/swagger-ui.html`
- **后果**: 易 — API 文档与代码同步，联调效率高，作品集可在线浏览接口；难 — 新增接口需同步添加注解（已作为开发习惯内化）。
