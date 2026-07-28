# ADR-013: 前端引入 Pinia 状态管理 + 中后台多页布局

- **状态**: 已采纳
- **上下文**: C 阶段目标——把作品集前端从"两页 demo"升级为"有架构感的中后台应用"，提升前端工程深度，与简历中"Vue3 + TS"技术栈相互印证。原 `hospital-web` 仅有门诊列表/详情两页，状态散在各组件（`reactive`），无统一状态管理与布局框架。
- **决策**:
   1. **状态管理**：引入 **Pinia**。认证状态从模块级 `reactive` 迁移为 `useAuthStore`；新增 `useVisitStore`（临床就诊）、`useMenuStore`（菜单树）、`usePatientStore`（患者域）、`useDispatchStore`（排队看板）、`useNotificationStore`（事件列表）、`useTabsStore`（多页签）六个领域 store。视图只渲染 + 触发动作，状态与异步逻辑收敛到 store。
   2. **布局**：新增 `layouts/MainLayout.vue`（Element Plus `el-container` + `el-aside` 侧边栏菜单 + `el-header` 顶栏用户信息/角色），所有页面以嵌套路由挂在主布局下；侧边栏用 `el-menu` `router` 模式，`meta.title` 驱动顶栏标题。
   3. **多页路由**：路由拆分为 工作台（Dashboard）/ 门诊就诊 / 就诊详情 / 门诊挂号 / 门诊大屏 / 消息通知 / 文件管理 / 患者管理 / 体检预约 / 我的预约 / 我的排队 / 我的报告 / 排队看板（Dispatch）/ 科室大屏 / 处方发药 / 处方详情 / 检验申请 / 检验详情 / 检查执行 / 报告管理 / 科室管理 / 员工管理 / 角色权限 / 菜单管理 / 收费管理 / 操作审计 / 404，共 29 条业务路由 + 全局 NotFound 兜底，`meta.requiresAuth` + 全局守卫。
   4. **页面厚度**：`NotificationView` 展示通知服务留痕的领域事件（事件驱动闭环）；`FileView` 经 Gateway 调 MinIO 上传（独立微服务闭环）；`DashboardView` 聚合各 store 概览。
   5. **配套后端**：为让通知页"真"有数据，`notification-service` 新增内存版 `NotificationStore`（演示用，非持久化）+ `GET /api/notify/events` 查询接口，消费端落痕。
- **后果**: 易 — 前端具备标准中后台工程结构（Pinia + 布局 + 多页），可直接写进简历；状态跨页共享、可测；事件驱动 / 微服务故事在 UI 上闭环；难 — 包体因 Element Plus 全量引入变大（JS chunk >500KB，属后续按需引入 `unplugin-vue-components` 优化点，当前不碰以免过早优化）；新增页面需遵循布局约定（刻意不做路由级权限码，权限仍走后端 `@PreAuthorize` + 前端按钮级 `hasAuthority`）。
