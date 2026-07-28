# ADR-011: 前端采用 Vue 3 + TypeScript + Vite（而非 React）

- **状态**: 已采纳
- **上下文**: 前端栈选型；用户技术栈与简历关键词均为 Vue 3 + Element Plus，且医院中后台场景以表格 / 表单 / 弹窗为主。
- **决策**: 前端统一采用 **Vue 3 + TypeScript + Vite + Element Plus + Vue Router + Axios**；`hospital-web` 经 Vite dev proxy 把 `/api` 转发至 API Gateway(:8104)，与部署形态一致。
- **后果**: 易 — 对齐用户既有积累、简历关键词一致、Element Plus 中后台组件开箱即用、出活快、组合式 API 学习曲线平缓；难 — 放弃了 React 生态的练习机会。
