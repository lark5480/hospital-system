# ADR-012: 认证采用自管 JWT + 七权分立 RBAC

- **状态**: 已采纳（**决策 2 / 5 / 6 已被取代**，见文末修订；现行口径以 [`../AGENTS.md`](../../AGENTS.md)「登录与账号」与 [`../review/2026-09-08-code-review-report.md`](../review/2026-09-08-code-review-report.md) 的 R-11 / R-34 / R-57 / R-62 为准）
- **上下文**: 本项目定位为学习 / 作品集，目标是把"统一认证 + 细粒度授权 + 七权分隔（录入/审核/执行/发药/收费/管理/预约）"作为可演示能力。需避免两个极端：① 裸奔无认证（无工程深度）；② 认证写死导致"不开外部 IdP 就跑不起来"（本地联调痛苦）。
- **决策**:
  1. **Authorization Server**：后端自管 JWT（`JwtTokenService` 签发/解析 HS256），无外部 IdP 依赖。
  2. ~~**资源服务**：`JwtAuthFilter` 校验 JWT 并注入 `SecurityContext`，默认态（`!iam` profile）全放行，iam 态（`@Profile("iam")`）校验。~~ **→ 已被取代，见修订。**
  3. **七权分隔 → RBAC**：`visit:entry / visit:audit / order:execute / pharmacy:dispense / charge:pay / system:admin / patient:booking` 七个 authority；岗位角色 `DOCTOR/NURSE/PHARMACIST/CASHIER/ADMIN` 映射到对应 authority。后端方法级 `@PreAuthorize("hasAuthority('visit:entry')")` 等；前端同源 authority 控制按钮。
  4. **令牌透传**：Gateway 透传 Bearer 令牌，下游各自再校验一次（纵深防御）。
  5. ~~**前端**：登录页换签 JWT，`Bearer` 经 Axios 拦截器附加；路由守卫 `requiresAuth`；开发态 `VITE_AUTH_ENABLED=false` 模拟全权限用户。~~ **→ `VITE_AUTH_ENABLED` 与 dev 模拟登录已删除，见修订。**
  6. ~~**当前用户身份解析**：统一经 `CurrentUserResolver.resolveUsername(request)` 取用户名（从 SecurityContext 或 `X-Username` 开发 fallback），**禁止**在业务代码用 `auth.getName()` 当业务主键。~~ **→ `X-Username` fallback 已移除，见修订；"禁止用 `auth.getName()` 当业务主键"这条仍然有效。**
- **后果**: 易 — 零外部依赖、本地零配置可跑、七权分立可演示、前后端权限同源；难 — 自管 JWT 需自行处理令牌刷新、过期等场景。
- **七权分立演示**：医生账号（录入+审核）能新建就诊但结算 403；收费员账号（收费）能结算但新建 403——直观体现医师不能代收费员结算。

## 修订（2026-09）：认证收敛为"单一真登录"，本 ADR 的决策 2 / 5 / 6 已废止

以下是**当前实现**，改代码前以 [`AGENTS.md`](../../AGENTS.md)「登录与账号」为准：

| 本 ADR 原条目 | 现状 | 出处 |
|---|---|---|
| 决策 2「默认态 `!iam` 全放行，iam 态才校验」 | **core 不再有"放行态"这个维度**：`anyRequest().authenticated()`，只额外放行 `/api/auth/**`、非 prod 的 swagger 路径，以及 `dispatcherTypeMatchers(ASYNC, ERROR)`（SSE 超时后容器会以 ASYNC 重进过滤器链，那次派发无身份上下文，见 R-65）。<br>**网关仍保留 `@Profile("!iam")` + `permitAll()`，且仓库不存在 `application-iam.yml`** → 网关实际恒为放行，**它不构成安全边界**，任何"靠网关挡住"的设计都不成立（R-62 的根因）。 | R-11 / R-62 / R-65 |
| 决策 5「开发态 `VITE_AUTH_ENABLED=false` 模拟全权限用户」 | **该变量已删除，dev 模拟登录整体移除**。唯一入口是 `POST /api/auth/login`（手机号 + 密码，JSON body），未登录由路由守卫跳 `/login`。**不要再引入任何形式的自动登录 / 免密切换**。 | R-12 / R-57 |
| 决策 6「`CurrentUserResolver` 支持 `X-Username` 开发 fallback」 | fallback 已移除，`resolveUsername()` 无参，仅从 SecurityContext 取 JWT `sub`（登录手机号）。详见 ADR-020 修订。 | ADR-020 |
| 后果「本地零配置可跑」 | 仍成立，但**代价已显式记录**：core 非 prod 未注入 `APP_JWT_SECRET` 时会生成随机密钥 → notification 校验不了 SSE ticket，只能选"放行 + WARN"。要验证完整鉴权链路必须给两个服务注入**同一把** `APP_JWT_SECRET`；prod 两侧均拒绝启动。 | R-01 / R-34 |

同期补上的原 ADR 未覆盖项（不属于"推翻决策"，属实现演进）：

- **BCrypt cost 10 → 12**，前置是登录失败 5 次锁 15 分钟 + IP 限流（R-10 / R-45）。
- **令牌可吊销**：`TokenRevocationService`（Redis 用户维度），改密/重置即吊销该用户全部 token；JWT TTL 8h → 4h。
  Redis 默认 fail-closed，不可用会拒绝全部请求（R-57 之外的「决策 5」实施项）。
- **SSE 不把长期 JWT 放进 URL**：先 `POST /api/core/sse/ticket` 换 60 秒 `scope=sse` 短期 ticket，
  且该 ticket 无法用于普通 API（core 与 notification 两侧都显式拒绝）（R-34）。
- **文件访问统一经 core 代理** `/api/core/files`，file-service 只内网可达；网关**不得**加回 `/api/files` 路由（R-62）。

