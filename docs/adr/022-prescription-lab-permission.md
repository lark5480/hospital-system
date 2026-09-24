# ADR-022: 处方与检验申请权限按实际业务流程收口（医生创建→药师发药）

- **状态**: 已采纳
- **上下文**: 需确定谁有权创建处方和检验申请。临床流程：医生开医嘱后直接创建处方/检验申请，收费员只负责结算，药师只负责发药。
- **决策**:
  1. `PrescriptionController.createFromVisit`：`@PreAuthorize("hasAuthority('visit:entry')")` —— 医生才能从就诊聚合药品医嘱生成处方。
  2. `PrescriptionController.dispense` / `cancel`：`@PreAuthorize("hasAuthority('pharmacy:dispense')")` —— 药师发药 / 取消。
  3. `LabController.createFromVisit`：`@PreAuthorize("hasAuthority('visit:entry')")` —— 医生创建检验申请。
  4. `LabController.submitResults` / `cancel`：`@PreAuthorize("hasAuthority('order:execute')")` —— 护士录入结果 / 取消。
  5. `DepartmentController`：读操作（list/get）需 `visit:entry` 或 `patient:booking`（C端患者可访问科室列表），写操作（create/update/delete）需 `system:admin`。`StaffController`：读操作需 `visit:entry`，写操作需 `system:admin`。
  6. 前端 `VisitDetailView` "创建处方" 按钮 `canEntry` 可见（医生）；`PharmacyPrescriptionsView` 发药 / 取消按钮 `canExecute` 可见（药师）；`LabRequisitionDetailView` 录入结果 / 取消同理。
  7. `BookingController.book` 由 `hasRole("patient")` 改为 `hasAuthority("patient:booking")`，与 ADR-012 新增的第七权（patient:booking）一致。
- **后果**: 易 — 权限与临床实际流程完全对齐，可在简历中展示"权限设计贴合业务"；难 — 需回归测试角色与 authority 映射（新加 `patient:booking`）。

## 修订（2026-09）

- 决策 1 / 3（`createFromVisit` 需 `visit:entry`）**仍成立但语义降级**：这两个端点已不再是临床入口。
  「确单 → 生成处方 / 检验申请」改由服务端事件驱动完成，见 [ADR-026](026-downstream-doc-events.md)；
  端点保留仅作**手工补建**，且"无新医嘱"时返回 200 + 既有单据（不再 409）。
- 决策 6（前端"创建处方"按钮 `canEntry` 可见）**已废止**：`VisitDetailView.vue` 中该按钮及其后的第二次请求已删除。
- 决策 2 / 4 / 5 / 7（发药、录入结果、科室与员工、`patient:booking`）**未变**，与代码一致。
