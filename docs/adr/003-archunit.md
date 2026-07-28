# ADR-003: 模块边界由 ArchUnit 强制

- **状态**: 已采纳
- **上下文**: 模块化单体常退化为大泥球。
- **决策**: CI 中 ArchUnit 校验依赖方向，违例即失败。
- **实施现状**: 模块边界与依赖方向实际由 **ArchUnit** 强制。域内事件走标准 `ApplicationEventPublisher`。ArchUnit 已有分层规则(`ArchitectureTest`)与一条模块隔离规则 `clinicalMustNotDependOnOtherModules`(clinical 不得依赖 pharmacy/lab/operation/integration)；**注意**：该规则以 clinical 为源**单向**守护，且 `operation`/`integration` 包当前不存在，故未覆盖 booking→patient 等其余模块间依赖——模块隔离范围待补。
- **后果**: 易 — 长期可维护；难 — 初期需定义清晰 API 边界，有少量前期成本。
