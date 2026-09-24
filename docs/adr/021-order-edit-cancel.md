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

## 修订（2026-09）

- 决策 1 / 2 原文写的「已执行或**已收费**的不动」，长期只有前半句成立：实现里两条路径都只看医嘱 `status == CREATED`，
  不看收费行。放过一次的后果是账实不一致 —— `cancelOrder` 只删 `UNPAID` 行，已 `PAID` 行会作为孤儿留下
  （钱收了、对应医嘱却被改掉或作废）。
- **现已补齐**：两条路径共用 `VisitService.assertOrderNotPaid`，存在 `PAID` 收费行时抛
  `IllegalStateException`（→ HTTP 409 + 中文提示「该医嘱已收费，不可修改/取消；请先退费」）。
  守护用例：`VisitServicePaidOrderGateTest`（含"无 PAID 行必须放行"与"退费路径不受影响"两个反向面）。
- 「后果」里"已收费订单不支持修改 / 取消，需退费流程，留作后续"至此**全部落地**：
  退费流程为 `refundOrder`（`charge:pay`，医嘱置 CANCELLED + `PAID` 行置 `REFUNDED` 且不物理删除）。
- 决策 3 与代码一致（`PUT` 改、`DELETE` 取消，均 `visit:entry` + `@AuditLog`）；
  后续新增的**退费**端点是另一条路径：`POST /api/core/visits/{id}/orders/{orderId}/refund`，需 `charge:pay`、动作名 `REFUND_ORDER`。
