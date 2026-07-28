# 医院信息系统 · hospital-system

> 个人学习 / 求职作品向项目。用 AI 辅助实践医院业务知识,循序渐进本地运行(不上公网/不付费服务器),DB 用 Oracle 转向 PostgreSQL。
> 结构详见 [`docs/architecture-design.md`](docs/architecture-design.md)，架构决策记录详见 [`docs/adr/`](docs/adr/README.md)。

## 技术栈

**后端**
- Java 21 + Spring Boot 3.2
- MyBatis-Plus + **PostgreSQL 16**
- RabbitMQ(事件桥接,默认启用)、MinIO(对象存储)
- 统一账号体系(手机号 + 密码,BCrypt 哈希)、自管 JWT 认证(无外部 IdP 依赖)、多角色支持、Spring @Scheduled(调度)
- Spring Cloud Gateway(统一入口)、GitHub Actions(CI)
- ArchUnit(模块分层红线 + 模块隔离,CI 强制的结构守护)

**前端**
- Vue 3 + TypeScript + Vite 5
- Element Plus、Vue Router、Pinia、Axios
- SPA:侧边栏菜单 + 嵌套路由

**中间件**(docker-compose 一键启动,4 个容器)
PostgreSQL 16 · Redis 7 · MinIO · RabbitMQ 3

## 结构总览

```
browser ──► hospital-web :5173
                │ /api (vite proxy)
                ▼
         API Gateway :8104
            │              │                   │
      hospital-core   notification        file-service
        :8101          :8102               :8103
   (模块化单体核心)   (RabbitMQ 消费)       (MinIO 封装)
```

- **hospital-core**:模块化单体,内部 10 个 domain 模块(`platform` 共享内核 + `clinical` / `pharmacy` / `lab` / `report` / `booking` / `dispatch` / `patient` / `iam` / `org` / `fhir`),各自独立 schema。
- **gateway**:路由 `/api/core/**`(转发 hospital-core 内全部业务域)、`/api/notify/**`(notification)、`/api/files/**`(file-service)、`/fhir/**`(FHIR R4)。
- **notification**:消费 `VisitCreatedEvent` / `OrderCreatedEvent` / `VisitStatusEvent`,写入 notification store,供前端轮询。
- **file**:MinIO 封装,上传下载 + 分片。

## 前端路由(29 条)

| 路由 | 视图 | 后端模块 |
|---|---|---|
| `/dashboard` | DashboardView | — |
| `/visits` `/visits/:id` | VisitListView / VisitDetailView | clinical |
| `/registration` `/registration/screen` | RegistrationView / OutpatientScreenView | clinical |
| `/notifications` | NotificationView | notification |
| `/files` | FileView | file |
| `/patient/registration` `/patient/booking` `/patient/appointments` `/patient/my-queue` `/patient/my-reports` | 对应 5 个 View | clinical / booking / booking / dispatch / report |
| `/patients` | PatientsView | patient |
| `/dispatch` `/dispatch/screen` | DispatchView / ScreenView | dispatch |
| `/pharmacy/prescriptions` `/pharmacy/prescriptions/:id` | 对应 2 个 View | pharmacy |
| `/lab/requisitions` `/lab/requisitions/:id` | 对应 2 个 View | lab |
| `/exams` | ExamTasksView | booking |
| `/reports` | ReportsView | report |
| `/org/departments` `/org/staff` `/org/roles` | DepartmentsView / StaffView / RoleAuthView | org / org / iam |
| `/menu-manage` | MenuManageView | iam |
| `/cashier` | CashierView | clinical |
| `/audit-logs` | AuditLogView | platform |
| `/login` `/404` | LoginView / NotFoundView | — |

## 认证与 RBAC(自管 JWT)

```
浏览器 ──(登录 /api/auth/login)──► hospital-core 签发 JWT ──► hospital-web
                                                                      │ Authorization: Bearer <JWT>
                                                                      ▼
                                                               API Gateway :8104
                                                         (透传至 hospital-core 校验)
```

- **自管 JWT**:后端 `JwtTokenService` 签发/解析(HS256),无外部 IdP 依赖,本地零配置可跑。
- **登录**:`POST /api/auth/login?phone=xxx&password=xxx` → 返回 token + roles + authorities(角色权限查库)。
- **RBAC**:七权(`visit:entry`/`visit:audit`/`order:execute`/`pharmacy:dispense`/`charge:pay`/`system:admin`/`patient:booking`),角色↔权限映射入库(`platform.role` + `platform.role_authority`),管理员后台可配。
- **菜单过滤**:后端 `MenuService` 从 `platform.menu` + `platform.menu_authority` 表加载菜单树,按当前用户 authorities 动态裁剪。
- **未来接外部 IdP**:只需替换 login 环节(校验外部 token → 换签自有 JWT),过滤器与 SecurityConfig 不动。

## 前端工程结构

```
hospital-web/src
├── layouts/MainLayout.vue     # 侧边栏菜单 + 嵌套布局
├── router/index.ts            # 路由定义(29 条)+ 全局守卫
├── stores/                    # Pinia 状态管理(auth/visit/menu/patient/dispatch/notification/tabs)
├── api/                      # Axios 实例 + 按域划分的 API 模块
├── views/                    # 29 个 Vue 组件(含 Login/NotFound)
└── types/                    # TypeScript 类型定义
```

## 快速开始

### 本地开发

```bash
# 1) 起中间件
docker compose up -d

# 2) 后端(4 个终端分别启动,默认模式)
mvn -pl hospital-core spring-boot:run              # :8101
mvn -pl hospital-notification-service spring-boot:run  # :8102
mvn -pl hospital-file-service spring-boot:run          # :8103
mvn -pl hospital-gateway spring-boot:run               # :8104

# 3) 前端
cd hospital-web
npm install && npm run dev              # :5173
```

### 测试账号(统一账号:手机号 + 密码,默认 123456)

| 手机号 | 密码 | 角色 | 能干啥 |
|---|---|---|---|
| `13800000001` | `123456` | doctor(DOCTOR) | 就诊(visit:entry+audit)、检查执行(order:execute)、患者管理 |
| `13800000003` | `123456` | nurse(NURSE) | 检验录入 / 检查执行(order:execute) |
| `13800000004` | `123456` | cashier(CASHIER) | 收费(charge:pay);**不能新建就诊** |
| `13800000006` | `123456` | pharmacist(PHARMACIST) | 发药(pharmacy:dispense) |
| `13800000010` | `123456` | nurse(NURSE)·检验科 | 检验/检查执行(order:execute),演示按执行科室路由 |
| `13800000000` | `123456` | admin(ADMIN) + patient(PATIENT) | 系统管理 + 角色权限配置;同时带患者角色可走 C 端预约 |
| `13700000000` | `123456` | patient(PATIENT) | 体检预约 / 查看自身数据(C 端) |

> 用医生手机号登录后点击"收费"按钮会被 403,这是七权分立的正确体现。
> `13800000000` 同时挂 ADMIN 与 PATIENT 两角色,菜单 = 两角色权限并集,正好演示「同一手机号多角色」。

### API 文档

启动后端服务后，可通过 Swagger UI 查看和测试 API：

- 直接访问 hospital-core：http://localhost:8101/swagger-ui.html
- 通过 API 网关：http://localhost:8104/swagger-ui.html

所有 Controller 已添加 OpenAPI 注解（@Tag、@Operation、@Parameter），文档按模块分组展示。

### 认证模式

统一真登录(手机号 + 密码,自管 JWT),无需额外配置;启动后访问页面会自动跳转登录页,用测试账号登录即可。

## RBAC 权限模型(统一账号 + 多角色)

七个 authority:`visit:entry` / `visit:audit` / `order:execute` / `pharmacy:dispense` / `charge:pay` / `system:admin` / `patient:booking`。

- **统一账号**:`platform.sys_user`(phone 唯一 + BCrypt 密码)。
- **多角色**:`platform.sys_user_role`(user_id × role_code 多对多),一个账号可同时绑定医生+患者+管理员。
- **权限并集**:登录时取所有角色的 authority 并集,菜单 = 并集可见。
- **后端鉴权**:`@PreAuthorize("hasAuthority('...')")` 标注在 controller 方法,由 `SecurityConfig` + `JwtAuthFilter` 统一收口。
- **前端菜单**:菜单结构存储在 `platform.menu` + `platform.menu_authority` 表,`MenuService` 按当前用户 authorities 动态过滤。
- **用户身份**:`CurrentUserResolver` 统一解析(从 SecurityContext 取 JWT sub,即登录手机号)。

## 模块规则(ArchUnit 红线)

CI 中 `mvn verify` 触发 `ArchitectureTest`:
- 分层:`api` ↛ `application` ↛ `domain` ↚ `infrastructure`
- 模块隔离:`clinical` 不依赖 `pharmacy` / `lab` / `operation` / `integration`
- `platform` 为共享内核,所有模块可依赖

## 目录

```
hospital-system/
├── pom.xml                      # Maven reactor 描述
├── docker-compose.yml           # 中间件编排
├── .env.example               # 环境变量模板
├── .github/workflows/ci.yml     # GitHub Actions CI
├── docs/architecture-design.md  # 架构设计 + ADR
├── hospital-core/               # 模块化单体核心
├── hospital-notification-service/ # 通知服务(RabbitMQ 消费者)
├── hospital-file-service/       # 文件服务(MinIO 封装)
├── hospital-gateway/           # API Gateway
└── hospital-web/                # Vue3 前端
```

## 业务闭环

#### 就诊 / 医嘱 / 收费(同一事务)
前端"就诊详情"页可:
- **追加医嘱**:选药品 / 检查 / 检验 → 数量 / 单价 → 提交。后端 `addOrder` 在同一 DB 事务内写入 `clinical.orders` 并生成 `clinical.charge`(UNPAID)。
- **收费**:点击"收费"按钮,后端 `pay` 在同一事务内把所有 UNPAID 收费置为 PAID。
- **修改/取消医嘱**:未执行(CREATED)的医嘱可修改(名称/数量/单价)或取消,已执行或已收费的不动。
- **退费**:已收费的 charge 可退费(REFUNDED)。
- 金额由后端 `unitPrice * quantity` 汇总,前端只作展示。

> 关键:就诊 / 医嘱 / 收费 *同一事务* 强一致落库(模块化单体的优势,无需 Saga / 最终一致性),这是本作品在「单体 vs 微服务」权衡中的核心判断点。

#### 门诊挂号与分诊排队
- **挂号**:患者选科室/医生,`POST /api/core/registrations` 创建 `clinical.registration`(WAITING)。
- **叫号**:医生 `POST /api/core/registrations/call-next`,把挂号置为 CALLED 并关联就诊单。
- **取消**:`POST /api/core/registrations/{id}/cancel` 取消挂号。
- **分诊屏**:`OutpatientScreenView` 展示当前科室排队状态。

#### 药事(处方→发药)
- **创建处方**:从就诊的药品医嘱(type=MEDICATION)自动聚合,生成处方 + 明细行。
- **发药**:标记处方为 DISPENSED,同步把关联医嘱状态回写为 EXECUTED。
- **取消**:取消处方与冲销。
- 药事模块应用完整 DDD 四层。

#### 检验(申请→结果)
- **申请**:从就诊的检验(type=LAB)医嘱自动聚合,生成 requisition + 明细。
- **录入结果**:在 `LabRequisitionDetailView` 录入每项结果(value/unit/reference)。
- **取消**:取消申请并冲销。
- **科室路由**:检验/检查医嘱带执行科室(`executionDeptId`),检验申请按执行科室过滤到对应科室人员处理。

#### 体检预约(套餐→号源→预约)
- **预约**:患者选套餐 → 选日期/时段 → 后端 `book()` 在 DB 行级锁下原子占号(`incrementBooked`,保证 `booked < capacity`)。
- **排队号**:预约成功后进入 `exam_task` + `queue_board`,`DispatchView` 看实时队列。
- **号源生成**:`@Scheduled` 定时调用 `SlotGenerateJob`(每日 +N 天)。
- 患者端:`/patient/booking`(预约)、`/patient/appointments`(我的预约)、`/patient/my-queue`(我的排队)、`/patient/my-reports`(我的报告)。

#### 报告
- **创建**:visit-finished 时由医生创建,status=DRAFT。
- **发布**:`POST /{id}/publish` 把报告置为 PUBLISHED 并入审计日志。
- 报告类型:LAB(检验综合)/EXAM(体检综合)/CLINIC(门诊病历)。

#### 结构化电子病历
- **目的**:将病历从纯文本升级为结构化存储,支持 JSONB 查询。
- **实现**:`clinical.medical_record` 表,JSONB 存储体格检查、诊断等半结构化数据。
- **关键类**:`MedicalRecord`, `MedicalRecordService`, `JsonbTypeHandler`。
- **API 端点**:`POST /api/core/medical-records`, `GET /api/core/medical-records?visitId=X`, `GET /api/core/medical-records/patient/{patientId}`。

#### 审计日志(AOP 切面)
关键写操作自动落 `audit_log` 表:
- `CREATE_VISIT` / `PAY_CHARGE` / `BOOK_APPOINTMENT` / `CREATE_PRESCRIPTION` / `DISPENSE` / `CREATE_REPORT` / `PUBLISH_REPORT` / `REGISTER` / `CALL_NEXT` / `EDIT_ORDER` / `CANCEL_ORDER` / `REFUND_ORDER` / `CONFIRM_VISIT` / `FINISH_VISIT`
- `@AuditLog` 注解 + `AuditLogAspect` 切面自动记录。

#### CQRS-lite 读模型
- **问题**:原 `listPage()` 加载全表到内存,存在 N+1 查询和 O(N²) 复杂度。
- **方案**:新建 `clinical.visit_read_model` 表,写操作同一事务内同步更新,读查询走读模型单表。
- **优化**:分页查询从 O(N²) 内存分页优化为 O(1) 数据库分页。
- **关键类**:`VisitReadModel`, `VisitReadModelService`, `VisitReadModelMapper`。

#### FHIR Facade
- **目的**:实现 FHIR R4 标准接口,支持医疗数据互联互通(互联互通测评方向)。
- **实现**:只读 API,手写 JSON 转换,不引入 HAPI FHIR 等重型库。
- **支持资源**:Patient, Encounter, Condition, CapabilityStatement。
- **API 端点**:`GET /fhir/Patient/{id}`, `GET /fhir/Encounter/{id}`, `GET /fhir/Condition/{id}`, `GET /fhir/metadata`。

## 学习路线图建议
1. 先跑通后端,理解模块化单体 + 事件驱动 + Gateway + 前端衔接。
2. 扩展 `clinical` 聚合(医嘱 / 收费 / 病历),利用单库事务。
3. 前端加固状态管理(Pinia),把"就诊详情 / 报告"做成多 Tab 多步骤。
4. 理解自管 JWT 真登录链路(手机号 + 密码,七权分立 RBAC)。
5. 调度两个每日任务:`SlotGenerateJob`(号源生成) + `AppointmentCleanupJob`(过期清理)(Spring @Scheduled)。
6. 扩展 `pharmacy` 模块,接入真实药品库存管理。
7. 把 `notification` 升级为真实短信/邮件推送,利用跨服务事件。
