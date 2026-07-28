# ADR-020: 当前用户身份解析约定（从 JWT 取用户名）

- **状态**: 已采纳
- **上下文**: C 端 patient 接口（`/patient/me`、`/dispatch/my-queue`）按当前登录用户查自己数据，初版直接 `SecurityContextHolder.getContext().getAuthentication().getName()` 取用户名；在自管 JWT 下 `getName()` 返回 JWT 的 `sub`，即登录用户名（如 `patient01`），符合预期。但开发态无 Bearer 头时需 fallback。
- **决策**:
  1. 新增 `CurrentUserResolver`（`platform/security` 包，核心内复用）：
     - 已认证态：从 `auth.getName()` 取用户名（自管 JWT 的 `sub` = 登录手机号）。
     - 开发态 fallback：从 `X-Username` 头取用户名（前端 Axios 拦截器在无 Bearer 时发送）。
  2. 所有"按当前用户查自己数据"的接口（`me()`、`myQueue()` 等）统一调用 `CurrentUserResolver.resolveUsername(request)`，**禁止**用 `auth.getName()` 当业务主键。
- **实施现状**：`PatientController.me()` 与 `DispatchController.myQueue()` 已改用 `CurrentUserResolver`；前端 `stores/patient.ts` 的 `fetchMe()` 已加 catch 防 404 冒泡。
- **后果**: 易 — 开发态与认证态都能正确识别当前登录用户；难 — 其他已用 `getName()` 当业务主键的老代码需逐一体检。
- **修订（2026-07）**：登录模式收敛为单一真登录（手机号 + 密码）后，`X-Username` 开发态 fallback 已移除，`resolveUsername()` 改为无参方法，仅从 SecurityContext 取 JWT `sub`（登录手机号）。
