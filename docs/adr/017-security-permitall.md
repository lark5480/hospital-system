# ADR-017: 默认（非 iam）态必须显式 permitAll 安全配置

- **状态**: 已采纳
- **上下文**: 用户本地 `npm run dev` 后全链路 401，且前端报 `getActivePinia() was called but there was no active Pinia`。排查发现两个 bug：① `SecurityConfig` 被 `@Profile("iam")` 独占，而 core/gateway/notification/file 四个服务均引入 `spring-boot-starter-security`；默认（非 iam）态下**没有任何 `SecurityFilterChain` bean**，Spring Boot 自动安全接管 → 全链路要求认证 → 401，与 ADR-012"默认态无外部 IdP 也能跑"的意图自相矛盾。② `main.ts` 在 `app.use(pinia)` 之前调用 `initAuth()`（内部 `useAuthStore()`），Pinia 尚未 active 抛错。
- **决策**:
  1. 为 `hospital-gateway`、`hospital-notification-service`、`hospital-file-service` 各补一个 `@Profile("!iam")` 的 `SecurityFilterChain`，`anyRequest().permitAll()` + 关闭 csrf，使默认态真正免鉴权，与前端 dev 态（不带令牌、模拟全权限用户）对齐；iam 态仍由各自 SecurityConfig 接管。
  2. `main.ts` 改为先 `createApp` + `app.use(pinia)` + `app.use(ElementPlus)`，再 `initAuth()`，最后 `app.use(router)` + `app.mount('#app')`，保证 `useAuthStore()` 调用时 Pinia 已 active。
- **后果**: 易 — 默认态零摩擦本地联调（前端不带令牌、后端全放行），与 ADR-012 设计意图一致，可直接 `npm run dev` 跑通；难 — 默认态因未启用 `@EnableMethodSecurity`，`@PreAuthorize` 等方法级注解不生效（仅放行不校验），故七权分立只能在 **iam 态**演示。
