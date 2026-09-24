# ADR-017: 默认（非 iam）态必须显式 permitAll 安全配置

- **状态**: ~~已采纳~~ → **已被取代（2026-09）**。本 ADR 记录的是一条**已废止且现在禁止**的做法：
  默认态 `permitAll` 会让"七权分立"根本无法演示，并直接构成 R-03 / R-11 / R-62 三起 Critical/High 的根因。
  **请勿照本 ADR 实施。** 现行口径：core / notification 为 `anyRequest().authenticated()` + 最小放行集
  （`/api/auth/**`、非 prod 的 swagger、`dispatcherTypeMatchers(ASYNC, ERROR)`），详见
  [ADR-012 修订](012-jwt-rbac.md) 与 [`AGENTS.md`](../../AGENTS.md)「登录与账号」。
  下文保留作为**决策过程记录**（当时为什么会走到这一步），不作为现状。
- **上下文**: 用户本地 `npm run dev` 后全链路 401，且前端报 `getActivePinia() was called but there was no active Pinia`。排查发现两个 bug：① `SecurityConfig` 被 `@Profile("iam")` 独占，而 core/gateway/notification/file 四个服务均引入 `spring-boot-starter-security`；默认（非 iam）态下**没有任何 `SecurityFilterChain` bean**，Spring Boot 自动安全接管 → 全链路要求认证 → 401，与 ADR-012"默认态无外部 IdP 也能跑"的意图自相矛盾。② `main.ts` 在 `app.use(pinia)` 之前调用 `initAuth()`（内部 `useAuthStore()`），Pinia 尚未 active 抛错。
- **决策**:
  1. 为 `hospital-gateway`、`hospital-notification-service`、`hospital-file-service` 各补一个 `@Profile("!iam")` 的 `SecurityFilterChain`，`anyRequest().permitAll()` + 关闭 csrf，使默认态真正免鉴权，与前端 dev 态（不带令牌、模拟全权限用户）对齐；iam 态仍由各自 SecurityConfig 接管。
  2. `main.ts` 改为先 `createApp` + `app.use(pinia)` + `app.use(ElementPlus)`，再 `initAuth()`，最后 `app.use(router)` + `app.mount('#app')`，保证 `useAuthStore()` 调用时 Pinia 已 active。
- **后果**: 易 — 默认态零摩擦本地联调（前端不带令牌、后端全放行），与 ADR-012 设计意图一致，可直接 `npm run dev` 跑通；难 — 默认态因未启用 `@EnableMethodSecurity`，`@PreAuthorize` 等方法级注解不生效（仅放行不校验），故七权分立只能在 **iam 态**演示。

## 修订（2026-09）

- **决策 1 废止**：`permitAll` 作为默认态是错的（见本 ADR「后果」自己写下的那句 —— 方法级注解不生效 ⇒ 七权分立根本无法演示）。
  core / notification 已收紧为 `authenticated()` + 最小放行集；file-service 靠 `InternalTokenFilter` 且只内网可达。
  **`hospital-gateway` 仍留着 `@Profile("!iam")` + `permitAll()`**，而仓库从未有过 `application-iam.yml` ——
  这正是 R-62 抓到的根因：**网关不是安全边界**，别把任何"挡住"的期望放在它身上。
- **决策 2 仍然有效，但细节变了**：`createApp` → `app.use(pinia)` → 再 `initAuth()` 的顺序保持不变（`useAuthStore()`
  必须在 Pinia active 之后调用）。变化在 Element Plus：R-39 后已改**按需引入**，不再有 `app.use(ElementPlus)`，
  取而代之的是**必须显式 `app.use(ElLoading)`** —— 按需插件只解析 `<el-xxx>` 标签、不解析 `v-loading` 指令，
  删掉它会让全仓 `v-loading` 静默失效（构建与类型检查都拦不住）。见 [`AGENTS.md`](../../AGENTS.md)「前端」。

