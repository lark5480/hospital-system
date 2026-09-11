# ADR-014: C端患者域与体检预约上下文（模块化单体新增 patient / booking 模块）

- **状态**: 已采纳
- **上下文**: 用户指出当前系统纯 B 端（医护后台），缺少 C 端患者入口，具体诉求是"患者预约体检、排队分发到各体检项"；同时确认用户体系此前只覆盖 B 端员工账号 + 七权，C 端患者身份/注册流是缺口，菜单级权限也未做。
- **决策**:
  1. 在 `hospital-core` 内新增两个包（沿用 clinical/platform 的 DDD 分层：domain / application / infrastructure / api），而非抽独立服务——与 ADR-001/009/010 一致，保持单库事务与低运维；C 端预约与 B 端临床/收费未来会经"任务流入"衔接，强一致更优。
     - `patient`：Patient 聚合（姓名/性别/生日/手机/身份证），`POST /api/patient/register` 按手机号幂等建档。
     - `booking`：ExamPackage（套餐）/ ExamItem（项目）/ Slot（号源）/ Appointment（预约单）。
  2. **号源不超卖**：用数据库原子占号——`SlotMapper.incrementBooked` 执行 `UPDATE booking.slot SET booked = booked + 1 WHERE id = ? AND booked < capacity`，返回受影响行数，0 即满；预约在 `@Transactional` 内先占号后落单，从根上杜绝超卖，不依赖应用层先查后改（有竞态）或乐观锁版本号（需额外字段）。
  3. **用户体系**：统一账号表 `platform.sys_user` 覆盖员工 + 患者，`patient01` 测试用户（密码 123456）；Gateway 对 `/api/patient/**` 仍路由到 hospital-core，`BookingController.book` 以 `@PreAuthorize("hasAuthority('patient:booking')")` 收口。
  4. **菜单级权限（轻量起步）**：`MainLayout` 按 `hasAuthority('patient:booking')` 渲染"体检预约（C端）"子菜单，无权限者根本看不到入口——这是菜单级权限的最小可用形态；完整的"后端返回菜单树"方案见后续 ADR-015。
- **后果**: 易 — C 端闭环已全部实现：患者自助注册 → 套餐浏览 → 号源预约 → 排队看板；5 个 C 端视图（/patient/booking /patient/registration /patient/appointments /patient/my-queue /patient/my-reports）+ 后端 patient:booking 权限 + 菜单级可见性；原子杜绝超卖；事件驱动排班（见 ADR-016）；报告闭环（见 ADR-019）。
- **修订（2026-08）**：
  1. C 端为演示闭环刻意简化：预约时自动标记 `PAID`，实付金额取套餐定价，未接支付网关（`BookingService.book`）。
  2. 预约接口（列表/详情/发起）增加归属校验（`CurrentUserResolver` + 当前患者比对，非本人 403），消除越权访问（见 ADR-020 修订）。
