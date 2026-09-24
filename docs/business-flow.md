# 医院信息系统 · 业务流程与角色权限矩阵
> 本文档覆盖系统的 B 端(医护后端)与 C 端(体检预约)的完整业务流程、各角色可执行动作与对应接口权限。
>
> **权威范围**:流程步骤、状态机、权限矩阵以本文为准(AGENTS.md 与 README 均指向此处)。
> **本文只描述现状**:每条结论都对应代码里的一个判断点,改流程时**同一次提交内**更新本文;
> 不要把"打算怎么改"写进来 —— 历史上 `assertNotConfirmed`、"确单后不可追加医嘱"等描述就是这样滞后于代码数月的。
> 决策理由(为什么这么设计)不在本文,见 [`docs/adr/`](adr/README.md);坑与铁律见 [`AGENTS.md`](../AGENTS.md)。

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

按岗位看(实际可登录的演示账号是手机号,清单见 [`README.md`](../README.md) 的「测试账号」小节):

| 动作 | 所需权限 | 医生 | 护士 | 收费员 | 药师 | 管理员 |
|---|---|---|---|---|---|---|
| 新建就诊 | visit:entry | ✅ | - | - | - | ✅ |
| 追加医嘱(药品/检查/检验) | visit:entry | ✅ | - | - | - | ✅ |
| 修改 / 取消医嘱(未执行**且未收费**) | visit:entry | ✅ | - | - | - | ✅ |
| 确单 | visit:entry | ✅ | - | - | - | ✅ |
| 完成就诊 | visit:audit | ✅ | - | - | - | ✅ |
| 处方 / 检验申请生成 | **服务端自动**(事件驱动,无人工动作);手工补建端点需 visit:entry | ✅ | - | - | - | ✅ |
| 发药 / 取消处方 | pharmacy:dispense | - | - | - | ✅ | ✅ |
| 录入检验结果 / 取消申请 | order:execute | - | ✅ | - | - | ✅ |
| 执行检查(B 超 / CT) | order:execute | - | ✅ | - | - | ✅ |
| 结算收费 | charge:pay | - | - | ✅ | - | ✅ |
| 退费 | charge:pay | - | - | ✅ | - | ✅ |
| 挂号 / 叫号 | visit:entry | ✅ | - | - | - | ✅ |
| 科室 / 员工管理 | system:admin | - | - | - | - | ✅ |
| 角色权限配置 | system:admin | - | - | - | - | ✅ |
| 菜单管理 | system:admin | - | - | - | - | ✅ |
| 文件管理 | system:admin | - | - | - | - | ✅ |
| 查看审计日志 | system:admin | - | - | - | - | ✅ |

> 表中权限是**接口上的 `@PreAuthorize` 要求**;医生账号能同时命中多行,是因为 `DOCTOR` 角色绑了 `visit:entry + visit:audit + order:execute` 三权。

### 医嘱类型分支

#### MEDICATION(药品)
```
医生开药品医嘱 → 医生确单 → 服务端自动生成/追加处方(无需人工点"创建处方")
   → 收费员结算该就诊单全部项目(charge:pay)
     → 药师发药(pharmacy:dispense, 系统校验已全部缴费)
       → 发药完成,自动生成门诊病历报告(PUBLISHED)
```

#### LAB(检验)
```
医生开检验医嘱 → 医生确单 → 服务端自动生成/追加检验申请
   → 护士录入结果(每项 resultValue/unit/refRange/abnormalFlag)
     → 自动生成 LAB 类型报告(PUBLISHED)
```

#### EXAM(检查)
```
医生开检查医嘱 → 医生 / 护士执行检查(order 标记 EXECUTED)
   → 自动生成 EXAM 类型报告(PUBLISHED)
```

> 处方与检验申请由**服务端在确单后生成**,不是医生手动创建、也不是前端追加的第二次请求 —— 见下节「确单 → 下游单据」。

### 就诊状态机

合法转换由 `VisitStatus.allowedTransitions()` 收口,一切变更必须经 `Visit.transitTo()`,非法转换抛 `IllegalStateException`:

| 转换 | 触发者 | 说明 |
|---|---|---|
| `CREATED → CONFIRMED` | `VisitService.confirm()` | 确单,幂等(已 CONFIRMED 直接返回);同时发布下游单据生成事件(见下节) |
| `CONFIRMED → IN_PROGRESS` | `VisitService.pay()` | **收费完成时自动推进**,没有手动"开始执行"入口 |
| `CONFIRMED → FINISHED` | `VisitService.finishVisit()` | 医生显式结束(未收费时也可,但见下方前置校验) |
| `IN_PROGRESS → FINISHED` | `VisitService.finishVisit()` | 同上,幂等(已 FINISHED 直接返回) |
| `FINISHED → *` | — | 终态,`isTerminal()` 为真 |

- `Order.status`:`CREATED → EXECUTED | CANCELLED`
- `Charge.payStatus`:`UNPAID → PAID | REFUNDED`
- `Registration.status`:`WAITING → CALLED | CANCELLED`
- 前进闸门:确单(`confirm`)后才能收费(`pay`,草稿态结算会被拒);**收费已结清**才能执行检查 / 发药 / 录入结果(`ChargeService.assertAllPaid` 前置校验)。
  `finishVisit` 的前置是「无 UNPAID 费用」,否则抛 `IllegalStateException("存在未缴费用…")`。
- **确单不等于冻结医嘱**:`FINISHED` 才拒绝追加医嘱(`addOrder` 以 `isTerminal()` 判断)。已确单/进行中照样可追加(即"回诊追加"),
  新医嘱会同步生成收费行并**自动并入既有下游单据**(见下节)。
- 修改 / 取消单条医嘱的闸门有**两道**：该医嘱自身为 `CREATED`（未执行），**且不存在已 `PAID` 的收费行**。
  与就诊处于哪个状态无关（草稿/已确单/进行中都可改）。已收费要改只能先走退费。
- `FINISHED` **不会**由"全部医嘱终结"自动触发,只由医生显式调用结束就诊产生。已缴费但未执行的医嘱默认保留不作废
  (患者仍可取药/检验),仅 `forceCancelOrders=true` 时批量作废并退费。

### 确单 → 下游单据(服务端事件驱动)

领域不变式:**就诊已确单 ⇒ 每条 LAB / MEDICATION 医嘱都被对应下游单据明细覆盖**。

- 生成由**服务端**完成,不经过 Controller、前端不发第二个请求:
  `confirm()`(批量,当时所有 `CREATED` 医嘱)与 `addOrder()`(就诊非草稿态时,单条即刻锁定)发布同一个
  `VisitOrdersConfirmedEvent`(自包含快照,医嘱 id 按 LAB / MEDICATION 分桶;两桶皆空不发)。
- 消费方:`VisitConfirmedLabListener` → 检验申请、`VisitConfirmedPrescriptionListener` → 处方,
  均为 `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`,并**刻意吞掉异常**——
  确单事务已提交,抛出只会让确单接口返回 500 而就诊其实已确单。
- 取舍:此处为**最终一致**(确单是临床主流程,不能被下游单据拖垮),代价是生成可能静默失败,由下述对账兜底。
- 兜底:`DownstreamDocReconcileJob`(`app.reconcile.cron`,默认每 10 分钟)补漏生成;正常每轮补建 0 单,
  一旦补建即 WARN。补建记录 actor 为 `system`,与人工可区分。
- 审计:因不走 Controller,`@AuditLog` 切面拦不到,由 `AuditRecorder` 在生成成功后显式补写,
  动作名沿用 `CREATE_REQUISITION` / `CREATE_PRESCRIPTION`,detail 带 `触发: 确单 / 确单后追加医嘱 / 对账补建`。
- `POST /api/lab/requisitions`、`/api/pharmacy/prescriptions` 仍可手工调用作为补建入口,但"无新医嘱"时返回
  **200 + 既有单据**(不再 409),以保证事件重投与对账幂等。

机制推导见 [ADR-026](adr/026-downstream-doc-events.md),发现过程与实证见 R-64([review 报告](review/2026-09-08-code-review-report.md))。

### 业务规则

1. **下游单据由服务端生成**(见上节),医生不需要、也不应该再手动"创建处方 / 创建检验申请";手工端点保留为补建入口。生成的单据不受收费限制,但**发药/执行/录入结果**受收费限制。
2. **发药前必须已收费**:发药前后端校验所有关联 charge 已 PAID,有 UNPAID 则拒绝发药。检查执行 / 检验结果录入同样经 `ChargeService.assertAllPaid` 前置。
3. **撤掉一条医嘱有两条不同路径**:未收费 → `cancelOrder`(医嘱置 CANCELLED,同时删除其 UNPAID 收费行);已收费 → `refundOrder`(医嘱置 CANCELLED,charge 置 REFUNDED 且不物理删除,保留审计)。
4. **报告自动发布**:LAB 结果录入完成 / EXAM 执行完成 / MEDICATION 发药完成,系统自动为该就诊生成对应类型的 PUBLISHED 报告。
5. **菜单级权限**:后端 `MenuService` 从 `platform.menu` + `platform.menu_authority` 表加载菜单树,按当前用户 authorities 动态裁剪。
6. **医嘱纠偏的双重闸门**:`editOrder` / `cancelOrder` 要求该医嘱为 `CREATED` **且无 `PAID` 收费行**
   (`assertOrderNotPaid`,命中则 409 + 中文提示"该医嘱已收费,不可修改/取消;请先退费")。
   > 这道闸门是 2026-09 补的。此前实现只看医嘱状态,能把已收费的医嘱改掉或作废,而 `cancelOrder` 只删 UNPAID 行,
   > 会留下"钱收了、对应医嘱却没了"的孤儿 `PAID` 行 —— 文档当时的"已收费不可改"是**写在纸上的意图,代码从未实现**。
   > 反向守护见 `VisitServicePaidOrderGateTest`(含"无 PAID 行时必须放行"与"退费路径不受影响")。
7. **门诊挂号**:患者选科室/医生 → 生成排队号(WAITING)→ 医生叫号(CALLED,关联就诊单)→ 取消(CANCELLED)。

---

## C 端业务流程(体检预约)

> 面向患者角色(演示账号 `13700000000`,或同时挂 PATIENT 角色的 `13800000000`)的自助体检预约流程。

### 整体流程

```
患者登录 → 浏览套餐 → 选日期/时段 → 确认预约(模拟支付) → 到院 → 进入排队
   │                         │                   │              │
   └─ /patient/booking ───────┘                   │              │
                        └─ 原子占号(不超卖) ────────┘              │
                                    └─ 事件驱动排班 → 看板 → 执行 → 报告
```

### 患者端菜单

| 页面 | 路由 | 功能 |
|---|---|---|
| 套餐预约 | `/patient/booking` | 浏览套餐、选日期/时段、确认预约 |
| 自助挂号 | `/patient/registration` | 患者自助门诊挂号(与 B 端挂号共用排队号) |
| 我的预约 | `/patient/appointments` | 查看已预约记录 |
| 我的排队 | `/patient/my-queue` | 实时排队状态(10 秒轮询) |
| 我的报告 | `/patient/my-reports` | 查看体检报告 |

### 关键机制

- **号源不超卖**:`SlotMapper.incrementBooked` 执行 `UPDATE booking.slot SET booked = booked + 1 WHERE id = ? AND booked < capacity`,返回受影响行数 0 即满;预约在 `@Transactional` 内先占号后落单。
- **事件驱动排班**:预约提交后发布 `AppointmentCreatedEvent`(自包含快照),`DispatchService` 消费 → 生成各工位 `ExamTask` + `QueueBoard` 投影行(CQRS 写读同库双写)。
- **号源生成(双兜底)**:定时任务 `SlotGenerateJob` 每日生成未来 14 天号源(capacity=3);`listSlots()` 查询时若无号源则懒加载未来 7 天(capacity=2)。
- **过期清理**:`@Scheduled` 每日 4:00 调用 `AppointmentCleanupJob`,过期未到院的 BOOKED 预约标记 CANCELLED 并释放号源。
- **预约状态机**:`BOOKED(已约) → CHECKED_IN(到院,首个体检任务开始) → DONE(全部任务完成)`;`CANCELLED` 取消。状态推进由 `AppointmentStatusEvent` 从 Dispatch 回写(幂等,禁止回退)。
- **模拟已支付(演示简化)**:C 端预约时自动标记 `PAID`,实付金额取套餐定价,未接真实支付网关——面向演示闭环刻意简化,后续可替换为支付回调。
- **自动出报告**:某预约全部体检任务进入终态(完成/跳过)且至少完成一项时,`DispatchService.maybeGenerateReport()` 自动生成已发布体检报告(EXAM 类型),并异步发布 `ReportPdfEvent` 预生成 PDF 上传 MinIO;跳过项不阻塞出报告,报告内标注未检项,全部跳过则不出报告。
- **排队分发操作集**:看板写操作除 `start`/`complete` 外,还有自动叫号 `call-next`、过号重排 `reorder-tail`、跳过 `skip`、重新排队 `requeue`,以及 SSE 大屏推送和 `PatientCalledEvent` 叫号事件(C 端我的排队 10 秒轮询 + 叫号横幅)。双重护栏:患者级单活跃(一次只在一科检查)+ 医生指定顺序(前序项目未完不叫后续)。全量操作集与取舍见 [ADR-016](adr/016-dispatch-engine.md)(含 2026-09 修订)。
- **患者身份(IDOR 防护)**:所有 C 端自助接口(`/patient/me`、`/patient/reports`、`/patient/appointments`、`/core/dispatch/my-queue`)均通过 `CurrentUserResolver` 解析 JWT `sub`(登录手机号)绑定当前登录用户;预约列表/详情/发起预约在服务端做**归属校验**,非本人预约一律 403。
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
