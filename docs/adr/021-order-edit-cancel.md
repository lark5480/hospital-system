# ADR-021: 就诊医嘱可修改 / 取消（医嘱纠偏与撤回）

- **状态**: 已采纳
- **上下文**: 医生在就诊详情页追加医嘱（药品 / 检查 / 检验）后，发现名称 / 数量 / 单价录错或患者不需要某项，当前无法修改只能整条重开，不符合临床纠偏流程。
- **决策**:
  1. `VisitService.editOrder(VisitId, OrderId, updates)`：只允许修改 `CREATED` 状态的医嘱（已执行或已关联已收费的不动），同步重算 `amount = quantity * unitPrice`，并把关联的 `clinical.charge` 记录中 `payStatus=UNPAID` 的项同步更新 `itemName` + `amount`。
  2. `VisitService.cancelOrder(VisitId, OrderId)`：只允许取消 `CREATED` 状态的医嘱，标记为 `CANCELLED`，并删除关联的 `clinical.charge` `UNPAID` 项（避免误收费）。
  3. 后端 `PUT /{id}/orders/{orderId}` 与 `DELETE /{id}/orders/{orderId}` 需 `visit:entry`，写入审计日志。
  4. 前端 `VisitDetailView` 的医嘱表对 `CREATED` 状态+ `canEntry` 权限暴露「修改 / 取消」按钮；修改走对话框复用 `orderForm` 结构。
  5. 新建就诊成功后前端自动 `router.push()` 到该就诊详情页（`VisitListView` 提交后返回 `created.id`）。
- **后果**: 易 — 临床医嘱全生命周期（开立 → 修改 → 取消 → 执行 → 收费）闭环，审计可追溯；与"就诊即入详情"联动，体验顺滑；难 — 收费状态更复杂化（已收费订单不支持修改 / 取消，需退费流程，留作后续）。
