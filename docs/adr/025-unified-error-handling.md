# ADR-025: 前后端统一错误处理

- **状态**: 已采纳
- **上下文**: 前后端错误处理分散在各 Controller 和页面组件中，格式不统一，前端缺少全局错误边界，异常场景用户体验差。
- **决策**:
  1. **后端**：`platform/config/GlobalExceptionHandler.java`（`@RestControllerAdvice`）统一捕获异常，返回标准 JSON 格式 `{timestamp, status, error, message}`：
     - `AccessDeniedException` → 403（权限不足）
     - `BadCredentialsException` → 401（认证失败）
     - `MethodArgumentNotValidException` → 400（参数校验失败）
     - `IllegalArgumentException` → 404（资源不存在）
     - `IllegalStateException` → 409（状态冲突）
     - `Exception` → 500（兜底）
     - `SecurityConfig` 同步适配认证/授权异常，统一走 JSON 响应。
  2. **前端**：
     - `ErrorBoundary.vue`：错误边界组件，捕获子组件渲染错误，展示降级 UI。
     - `main.ts`：全局 `errorHandler` + `unhandledrejection` 监听，兜底未捕获异常。
     - `http.ts`：Axios 响应拦截器统一提示 403/404/500/网络错误，使用 Element Plus `ElMessage` 展示。
  3. 6 个 Controller 移除冗余 `@ExceptionHandler`，收敛到全局统一处理。
- **后果**: 易 — 全链路错误格式统一，前端不白屏，后端不泄露堆栈；难 — 特殊业务异常需自定义异常类扩展（当前通过 `IllegalArgumentException`/`IllegalStateException` 覆盖主要场景）。
