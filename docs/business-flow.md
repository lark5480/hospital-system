# 医院信息系统 · 业务流程与角色权限矩阵
> 本文档覆盖系统的 B 端(医护后端)与 C 端(体检预约)的完整业务流程、各角色可执行动作与对应接口权限。

## B 端业务流程(门诊就诊)

### 整体流程

```
医生开医嘱 → 医生创建处方 → 收费员收钱 → 药师发药
   │                      │                  │            │
   ├─ MEDICATION ───────→ 创建处方 ──→ 收费 ──→ 发药
   ├─ EXAM ────────────→ 医生执行 → 自动生成报告
   └─ LAB ─────────────→ 医生创建申请 → 护士录入结果 → 自动生成报告
```

### 角色权限矩阵

| 岗位 | 角色编码 | authorities(可后台配) | 可访问菜单 |
|---|---|---|---|
| **医生** | `DOCTOR` | visit:entry, visit:audit, order:execute | 工作台/就诊/患者/通知/报告/排队看板/医技管理 |
| **护士** | `NURSE` | order:execute | 工作台/医技管理(检验) |
| **收费员** | `CASHIER` | charge:pay | 工作台/收费管理 |
| **药师** | `PHARMACIST` | pharmacy:dispense | 工作台/药事管理 |
| **管理员** | `ADMIN` | 全部 6 权 | 全部 |
| **患者** | `PATIENT` | patient:booking | 工作台+体检预约(C端) |

> 角色↔权限映射入库(`platform.role_authority`),管理员可在"角色权限管理"后台动态调整,不再改代码/JSON。

### 动作权限明细

| 动作 | 所需权限 | doctor01 | nurse01 | cashier01 | pharmacist01 | admin01 |
|---|---|---|---|---|---|---|
| 新建就诊 | visit:entry | ✅ | - | - | - | ✅ |
| 追加医嘱(药品/检查/检验) | visit:entry | ✅ | - | - | - | ✅ |
| 修改 / 取消医嘱(未执行) | visit:entry | ✅ | - | - | - | ✅ |
| 创建处方 | visit:entry | ✅ | - | - | - | ✅ |
| 发药 / 取消处方 | pharmacy:dispense | - | - | - | ✅ | ✅ |
| 创建检验申请 | visit:entry | ✅ | - | - | - | ✅ |
| 录入检验结果 / 取消申请 | order:execute | - | ✅ | - | - | ✅ |
| 执行检查(B 超 / CT) | order:execute | - | ✅ | - | - | ✅ |
| 结算收费 | charge:pay | - | - | ✅ | - | ✅ |
| 科室 / 员工管理 | system:admin | - | - | - | - | ✅ |
| 文件管理 | system:admin | - | - | - | - | ✅ |

### 医嘱类型分支

#### MEDICATION(药品)
```
医生开药品医嘱 → 医生创建处方(visit:entry, 聚合该就诊所有药品 ORDER)
   → 收费员结算该就诊单全部项目(charge:pay)
     → 药师发药(pharmacy:dispense, 系统校验已全部缴费)
       → 发药完成,自动生成门诊病历报告(PUBLISHED)
```

#### LAB(检验)
```
医生开检验医嘱 → 医生创建检验申请(order 聚合)
   → 护士录入结果(每项 resultValue/unit/refRange/abnormalFlag)
     → 自动生成 LAB 类型报告(PUBLISHED)
```

#### EXAM(检查)
```
医生开检查医嘱 → 医生 / 护士执行检查(order 标记 EXECUTED)
   → 自动生成 EXAM 类型报告(PUBLISHED)
```

### 就诊状态机

- `Visit.status`:`CREATED → CONFIRMED → IN_PROGRESS → FINISHED`
- `Order.status`:`CREATED → EXECUTED | CANCELLED`
- `Charge.payStatus`:`UNPAID → PAID`
- 前进闸门:确单(`confirm`)后才能收费(`pay`);**收费已结清**才能执行检查 / 发药 / 录入结果(`ChargeService.assertAllPaid` 前置校验);全部医嘱终结后自动 `FINISHED`。
- 注意:「创建处方」「创建检验申请」会顺带调用 `visitService.confirm()` 锁定就诊单,锁定后不可再追加医嘱(`addOrder` 受 `assertNotConfirmed` 约束),操作顺序需留意。

### 业务规则

1. **处方由医师创建**(visit:entry),不受收费限制。医生在就诊详情确认所有医嘱后即可创建处方,收费员只负责结算。
2. **发药前必须已收费**:发药前后端校验所有关联 charge 已 PAID,有 UNPAID 则拒绝发药。
3. **收费不可退**:已结算的 charge 不退费,关联医嘱不可修改 / 取消。
4. **报告自动发布**:LAB 结果录入完成 / EXAM 执行完成 / MEDICATION 发药完成,系统自动为该就诊生成对应类型的 PUBLISHED 报告。
5. **菜单级权限**:前端 `MenuService` 按当前用户 authorities 裁剪菜单树,无权限的菜单项不渲染。
6. **医嘱纠偏**:未执行的医嘱(CREATED)可修改(名称/数量/单价)或取消,已执行或已收费的不动。

---

## C 端业务流程(体检预约)

> 面向患者(patient01)的自助体检预约流程。

### 整体流程

```
患者登录 → 浏览套餐 → 选日期/时段 → 确认预约 → 进入排队
   │                         │                   │              │
   └─ /patient/booking ───────┘                   │              │
                       └─ 原子占号(不超卖) ────────┘              │
                                   └─ 事件驱动排班 → 看板 → 执行 → 报告
```

### 患者端菜单

| 页面 | 路由 | 功能 |
|---|---|---|
| 套餐预约 | `/patient/booking` | 浏览套餐、选日期/时段、确认预约 |
| 我的预约 | `/patient/appointments` | 查看已预约记录 |
| 我的排队 | `/patient/my-queue` | 实时排队状态(10 秒轮询) |
| 我的报告 | `/patient/my-reports` | 查看体检报告 |

### 关键机制

- **号源不超卖**:`SlotMapper.incrementBooked` 执行 `UPDATE booking.slot SET booked = booked + 1 WHERE id = ? AND < capacity`,返回受影响行数 0 即满。
- **事件驱动排班**:预约提交后发布 `AppointmentCreatedEvent`(自包含快照),`DispatchService` 消费 → 生成各工位 `ExamTask` + `QueueBoard` 投影行。
- **号源生成**:`@Scheduled` 每日定时调用 `SlotGenerateJob`(未来 N 天号源)。
- **过期清理**:`@Scheduled` 定时调用 `AppointmentCleanupJob`。
- **患者身份**:C 端接口通过 `CurrentUserResolver` 解析,与登录用户绑定(自管 JWT)。
- **权限**:C 端菜单需 `patient:booking` authority,仅患者可见。

### 排队分发权限

排队看板写操作(`start`/`complete`/`call-next`/`reorder-tail`/`skip`)需要以下任一权限:
- `visit:entry` (医生)
- `visit:audit` (主任医师)
- `order:execute` (护士/技师)
- `charge:pay` (收费员)
- `system:admin` (管理员)

> 注意:药师(`pharmacy:dispense`)不参与排队分发流程,无权操作看板。

---

## 架构决策记录(ADR 索引)

| ADR | 标题 | 状态 |
|---|---|---|
| ADR-001 | 模块化单体而非微服务 | Accepted |
| ADR-002 | 单一关系库 + 每模块独立 schema | Accepted |
| ADR-003 | 模块边界由 ArchUnit 强制 | Accepted |
| ADR-004 | 互操作层讲 HL7 v2 + FHIR R4 | Accepted |
| ADR-005 | 本地运行(Docker Compose) | Accepted |
| ADR-006 | 统一 IAM:默认自管 JWT,预留外部 IdP 接入 | Accepted |
| ADR-007 | 单体阶段中间件范围 | Accepted |
| ADR-008 | 日志存储:Loki + PostgreSQL 审计 | Accepted |
| ADR-009 | 微服务 vs 模块化单体再确认 | Accepted |
| ADR-010 | 作品集采用混合形态 | Accepted |
| ADR-011 | 前端采用 Vue 3 + TypeScript + Vite | Accepted |
| ADR-012 | 自管 JWT + 七权分立 RBAC | Accepted |
| ADR-013 | 前端引入 Pinia + 中后台布局 | Accepted |
| ADR-014 | C 端患者域与体检预约 | Accepted |
| ADR-015 | 菜单级权限后端驱动 | Accepted |
| ADR-016 | 排队分发引擎(事件驱动 + CQRS) | Accepted |
| ADR-017 | 默认态显式 permitAll | Accepted |
| ADR-018 | AMQP 事件桥接默认启用 | Accepted |
| ADR-019 | 报告模块作为独立限界上下文 | Accepted |
| ADR-020 | 当前用户身份解析约定 | Accepted |
| ADR-021 | 就诊医嘱可修改 / 取消 | Accepted |
| ADR-022 | 处方与检验权限按业务流程收口(医生创建→药师发药) | Accepted |
| ADR-023 | JWT 自动刷新(axios 静默续期) | Accepted |