# ADR-015: 菜单级权限后端驱动（DB 存储 + 管理后台）

- **状态**: 已采纳
- **上下文**: ADR-014 仅以前端 `hasAuthority('patient:booking')` 粗筛一个子菜单，菜单项仍硬编码在 `MainLayout`，违背"菜单唯一事实源应在后端"的原则；新增上下文（如排队看板）时前端也要跟着改，耦合。
- **决策**:
  1. `hospital-core` 新增 `iam` 包：菜单数据存储在 `platform.menu` + `platform.menu_authority` 表；`MenuService` 从 DB 加载菜单树并按当前用户 authorities **递归裁剪**；`MenuController`（`GET /api/core/iam/menu`）返回当前用户可见菜单树；`MenuManageController`（`GET/POST/PUT/DELETE /api/core/iam/menu-manage/**`）提供菜单 CRUD 管理后台（需 `system:admin`）。
  2. 权限语义：节点 `authorities` 为空 = 任意已登录可见；非空 = 需拥有其中至少一个；分组节点只要有任一可见子项即保留。图标以**字符串**下发，前端经 `icon-map` 映射，后端不耦合 UI 组件。
  3. 前端：`types/menu.ts` + `api/menu.ts` + `stores/menu.ts`（Pinia, onMounted 拉取）+ `layouts/icon-map.ts` + `components/MenuNode.vue`（递归渲染分组/叶子，自 import 实现递归）；`MainLayout` 改为 `<MenuNode :items="menu.menus" />`，移除全部硬编码菜单与 `hasAuthority('patient')` 分支。
- **后果**: 易 — 菜单级权限真正后端驱动、DB 存储、管理员后台可配、改菜单不动前端；难 — 多一个后端端点与一次首屏请求（可缓存/随登录刷新）。
