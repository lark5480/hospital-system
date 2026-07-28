# 医院信息系统 · 业务流程与角色权限矩阵
> 本文档覆盖系统的 B 端(医护后端)与 C 端(体检预约)的完整业务流程、各角色可执行动作与对应接口权限。

## B 端业务流程(门诊就诊)

### 整体流程

```
患者挂号 → 医生叫号 → 医生开医嘱 → 医生创建处方 → 收费员收钱 → 药师发药
   │           │            │                      │                  │            │
   └─ 排队等候 ┘            ├─ MEDICATION ───────→ 创建处方 ──→ 收费 ──→ 发药
                            ├─ EXAM ────────────→ 医生执行 → 自动生成报告
                            └─ LAB ─────────────→ 医生创建申请 → 护士录入结果 → 自动生成报告
```

### 挂号流程详细说明

#### 前置条件
- 患者已在系统中建档（`patient` 表有记录）
- 科室已配置（`org.department` 表有记录）
- 挂号员具有 `visit:entry` 权限

#### 挂号操作步骤
1. **选择患者**：挂号员从患者列表中检索并选择目标患者
2. **选择科室**：为患者选择就诊科室（系统展示当前可用科室列表）
3. **生成排队号**：系统自动按科室当日自增序号生成排队号，记录状态为 `WAITING`
4. **患者候诊**：患者持排队号在对应科室等候

#### 叫号与就诊衔接
1. **医生/挂号员点击"叫下一位"**：系统从该科室 `WAITING` 状态中取排队号最小的记录
2. **自动创建就诊单**：系统将挂号记录状态更新为 `CALLED`，同时自动创建 `clinical.visit`（状态 `CREATED`），关联 `visitId`
3. **医生接诊**：医生在工作台看到已叫号的就诊单，开始录入医嘱

#### 状态流转
```
Registration.status: WAITING → CALLED → (就诊完成)
                                ↘ CANCELLED (取消)
Visit.status: (无) → CREATED → CONFIRMED → IN_PROGRESS → FINISHED
```

#### 异常处理
- **患者未到**：挂号员可将 `WAITING` 状态的挂号记录取消（`CANCELLED`），不创建就诊单
- **重复挂号**：同一患者同一科室当日已有 `WAITING` 或 `CALLED` 记录时，系统提示"已在候诊中"
- **叫号后患者未到**：医生可继续叫下一位，前一位仍为 `CALLED` 状态，不会自动取消

### 角色权限矩阵

| 岗位 | 角色编码 | authorities(可后台配) | 可访问菜单 |
|---|---|---|---|
| **医生** | `DOCTOR` | visit:entry, visit:audit, order:execute | 工作台/就诊/挂号/患者/通知/报告/排队大屏/检查执行/医技管理 |
| **护士** | `NURSE` | order:execute | 工作台/医技管理(检验) |
| **收费员** | `CASHIER` | charge:pay | 工作台/收费管理 |
| **药师** | `PHARMACIST` | pharmacy:dispense | 工作台/药事管理 |
| **管理员** | `ADMIN` | 全部 7 权 | 全部 |
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
| 退费 | charge:pay | - | - | ✅ | - | ✅ |
| 确单 / 完成就诊 | visit:entry / visit:audit | ✅ | - | - | - | ✅ |
| 挂号 / 叫号 | visit:entry | ✅ | - | - | - | ✅ |
| 科室 / 员工管理 | system:admin | - | - | - | - | ✅ |
| 角色权限配置 | system:admin | - | - | - | - | ✅ |
| 菜单管理 | system:admin | - | - | - | - | ✅ |
| 文件管理 | system:admin | - | - | - | - | ✅ |
| 查看审计日志 | system:admin | - | - | - | - | ✅ |

### 医嘱类型分支

#### MEDICATION(药品)
```
医生开药品医嘱 → 医生创建处方(Visit:entry, 聚合该就诊所有药品 ORDER)
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
  - 转换由 `VisitStatus` 枚举收口,非法转换抛 `IllegalStateException`
  - `CREATED` → `CONFIRMED`:`confirm()` 确单,锁定后不可追加医嘱
  - `CONFIRMED` → `IN_PROGRESS` / `FINISHED`:`finish()` 完成就诊
  - `IN_PROGRESS` → `FINISHED`:`finish()` 完成就诊
- `Order.status`:`CREATED → EXECUTED | CANCELLED`
- `Charge.payStatus`:`UNPAID → PAID | REFUNDED`
- `Registration.status`:`WAITING → CALLED | CANCELLED`
- 前进闸门:确单(`confirm`)后才能收费(`pay`);**收费已结清**才能执行检查 / 发药 / 录入结果(`ChargeService.assertAllPaid` 前置校验);全部医嘱终结后自动 `FINISHED`。
- 注意:「创建处方」「创建检验申请」会顺带调用 `visitService.confirm()` 锁定就诊单,锁定后不可再追加医嘱(`addOrder` 受 `assertNotConfirmed` 约束),操作顺序需留意。

### 业务规则

1. **处方由医师创建**(visit:entry),不受收费限制。医生在就诊详情确认所有医嘱后即可创建处方,收费员只负责结算。
2. **发药前必须已收费**:发药前后端校验所有关联 charge 已 PAID,有 UNPAID 则拒绝发药。
3. **收费可退**:已结算的 charge 可退费(REFUNDED),关联医嘱不可修改 / 取消。
4. **报告自动发布**:LAB 结果录入完成 / EXAM 执行完成 / MEDICATION 发药完成,系统自动为该就诊生成对应类型的 PUBLISHED 报告。
5. **菜单级权限**:后端 `MenuService` 从 `platform.menu` + `platform.menu_authority` 表加载菜单树,按当前用户 authorities 动态裁剪。
6. **医嘱纠偏**:未执行的医嘱(CREATED)可修改(名称/数量/单价)或取消,已执行或已收费的不动。
7. **门诊挂号**:患者选科室/医生 → 生成排队号(WAITING)→ 医生叫号(CALLED,关联就诊单)→ 取消(CANCELLED)。

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

## 架构决策记录

详见 [ADR 目录](adr/README.md)，包含所有架构决策的完整记录。
