# ADR-012: 认证采用自管 JWT + 七权分立 RBAC

- **状态**: 已采纳
- **上下文**: 本项目定位为学习 / 作品集，目标是把"统一认证 + 细粒度授权 + 七权分隔（录入/审核/执行/发药/收费/管理/预约）"作为可演示能力。需避免两个极端：① 裸奔无认证（无工程深度）；② 认证写死导致"不开外部 IdP 就跑不起来"（本地联调痛苦）。
- **决策**:
  1. **Authorization Server**：后端自管 JWT（`JwtTokenService` 签发/解析 HS256），无外部 IdP 依赖。
  2. **资源服务**：`JwtAuthFilter` 校验 JWT 并注入 `SecurityContext`，默认态（`!iam` profile）全放行，iam 态（`@Profile("iam")`）校验。
  3. **七权分隔 → RBAC**：`visit:entry / visit:audit / order:execute / pharmacy:dispense / charge:pay / system:admin / patient:booking` 七个 authority；岗位角色 `DOCTOR/NURSE/PHARMACIST/CASHIER/ADMIN` 映射到对应 authority。后端方法级 `@PreAuthorize("hasAuthority('visit:entry')")` 等；前端同源 authority 控制按钮。
  4. **令牌透传**：Gateway 透传 Bearer 令牌，下游各自再校验一次（纵深防御）。
  5. **前端**：登录页换签 JWT，`Bearer` 经 Axios 拦截器附加；路由守卫 `requiresAuth`；开发态 `VITE_AUTH_ENABLED=false` 模拟全权限用户。
  6. **当前用户身份解析**：统一经 `CurrentUserResolver.resolveUsername(request)` 取用户名（从 SecurityContext 或 `X-Username` 开发 fallback），**禁止**在业务代码用 `auth.getName()` 当业务主键。
- **后果**: 易 — 零外部依赖、本地零配置可跑、七权分立可演示、前后端权限同源；难 — 自管 JWT 需自行处理令牌刷新、过期等场景。
- **七权分立演示**：`doctor01`（录入+审核）能新建就诊但结算 403；`cashier01`（收费）能结算但新建 403——直观体现 医师不能代收费员结算。
