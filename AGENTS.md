# AGENTS.md

本文件为 Codex（codex.ai/code）在本仓库中协作时提供指引。

## 常用命令

```bash
# 中间件（一键启动全部）
docker compose up -d

# 构建全部后端模块（跳过测试以提速）
mvn clean install -DskipTests

# 构建 + 测试（CI 流水线）
mvn -B clean verify

# 运行单个测试类
mvn -pl hospital-core -Dtest=BookingServiceTest test

# 仅运行架构边界测试（ArchUnit）
mvn -pl hospital-core -Dtest=ArchitectureTest test

# 运行后端服务（4 个终端分别启动）
mvn -pl hospital-core spring-boot:run                          # :8101
mvn -pl hospital-notification-service spring-boot:run          # :8102
mvn -pl hospital-file-service spring-boot:run                  # :8103
mvn -pl hospital-gateway spring-boot:run                       # :8104

# 前端
cd hospital-web
npm install
npm run dev              # :5173，经 Vite 代理转发至网关
npm run build            # 类型检查 + 生产构建
npm run type-check       # 仅做 vue-tsc 类型检查
```

## 项目架构

**混合形态：** 模块化单体核心 + 2 个抽出服务，统一置于 API 网关之后。

```
浏览器（hospital-web :5173）
  | /api（Vite 代理）
  v
API 网关（hospital-gateway :8104）
  |-- /api/core/**   -> hospital-core（:8101）   [模块化单体]
  |                      - clinical（就诊、医嘱、收费）
  |                      - pharmacy（处方、发药）
  |                      - lab（检验申请、结果）
  |                      - booking（预约、套餐、号源）
  |                      - dispatch（排队引擎）
  |                      - patient（患者注册）
  |                      - report（医疗报告）
  |                      - iam（菜单/RBAC）
  |                      - org（科室、员工）
  |                      - fhir（FHIR R4 标准接口）  [10 个业务域]
  |                      - platform（共享内核：审计日志、消息、任务调度）——非业务模块，所有模块可依赖
  |-- /api/notify/**  -> notification-service（:8102）  [事件消费者]
  |-- /api/files/**   -> file-service（:8103）           [MinIO 封装]
```

### 后端模块（Maven 多模块）

| 模块 | 职责 |
|---|---|
| `hospital-core` | 模块化单体——内含 10 个业务域模块，各自遵循 DDD 分层 |
| `hospital-gateway` | Spring Cloud Gateway——路由 `/api/core/**`、`/api/notify/**`、`/api/files/**` |
| `hospital-notification-service` | RabbitMQ 消费者——监听 `VisitCreatedEvent` |
| `hospital-file-service` | MinIO 封装——文件上传/下载 |

### 单模块 DDD 结构（hospital-core 内部）

每个业务域模块遵循 4 层：

```
clinical/          （示例模块）
  ├── domain/         实体、值对象（Visit、Order、Charge）
  ├── application/    服务类（VisitService）
  ├── infrastructure/ MyBatis-Plus Mapper（VisitMapper extends BaseMapper<Visit>）
  └── api/            REST 控制器（VisitController）
```

由 CI 中的 ArchUnit（ArchitectureTest）强制：`api` → `application` → `domain` ← `infrastructure`。

### 模块隔离规则

- `clinical` 不得依赖 `pharmacy`、`lab`、`operation`、`integration`
- `platform` 是共享内核——所有模块均可依赖
- ArchUnit 规则在 `mvn verify` 期间运行，违例即构建失败

关键写操作通过 `@AuditLog` 注解 + `AuditLogAspect` 切面自动记录到 `audit_log` 表，支持 Visit/Department/Staff/Menu/Role/Appointment 等实体。

### 数据库：按模块划分 schema 隔离

所有表都在同一个 PostgreSQL 16 实例（`hospital` 库）中，按 schema 隔离：

| Schema | 模块 |
|---|---|
| `platform` | audit_log、sys_user、sys_user_role、role、role_authority |
| `clinical` | visit、orders、charge、registration、visit_read_model（CQRS）、medical_record（JSONB） |
| `patient` | patient |
| `booking` | exam_package、exam_item、slot、appointment |
| `dispatch` | exam_task、queue_board |
| `pharmacy` | prescription、prescription_item |
| `lab` | requisition、result_item |
| `report` | record |
| `org` | department、staff |

ORM：MyBatis-Plus 3.5.7，`@TableName("schema.table")`，`map-underscore-to-camel-case: true`。

### 事件驱动模式

- **进程内：** Spring `ApplicationEventPublisher` 处理 hospital-core 内部的领域事件
- **跨进程：** RabbitMQ 桥接——`VisitEventAmqpBridge` 把 `VisitCreatedEvent` 发布到 `hospital.exchange`，由 notification-service 消费
- **调度：** `@Scheduled`——`SlotGenerateJob`（每天 3:00 生成号源）、`AppointmentCleanupJob`（每天 4:00 清理过期预约）

### P2 特性（已完成）

#### CQRS-lite 读模型

- **目的：** 优化就诊列表查询性能，解决 N+1 查询和内存分页问题
- **实现：** `clinical.visit_read_model` 表，写操作在同一事务内同步更新
- **关键类：** `VisitReadModel`、`VisitReadModelMapper`、`VisitReadModelService`
- **查询优化：** `listPage()` 从 O(N²) 内存分页优化为 O(1) 数据库分页

#### FHIR Facade

- **目的：** 实现 FHIR R4 标准接口，支持医疗数据互联互通
- **实现：** 只读 API，手写 JSON 转换，不引入 HAPI FHIR 等重型库
- **支持资源：** Patient、Encounter、Condition、CapabilityStatement
- **API 端点：** `/fhir/Patient`、`/fhir/Encounter`、`/fhir/Condition`、`/fhir/metadata`

#### 结构化电子病历

- **目的：** 将病历从纯文本升级为结构化存储，支持 JSONB 查询
- **实现：** `clinical.medical_record` 表，用 JSONB 存储体格检查、诊断等半结构化数据
- **关键类：** `MedicalRecord`、`MedicalRecordMapper`、`MedicalRecordService`
- **自定义 TypeHandler：** `JsonbTypeHandler` 解决 MyBatis-Plus + PostgreSQL JSONB 兼容问题

### 统一账号体系（手机号 + 密码 + 多角色）

- **统一账号表：** `platform.sys_user`（phone 唯一 + BCrypt 密码）、`platform.sys_user_role`（user_id × role_code 多对多）
- **多角色权限并集：** 登录后 JWT 含 roles（多角色）+ authorities（七权并集），菜单 = 并集可见
- 七权 RBAC：`visit:entry` / `visit:audit` / `order:execute` / `pharmacy:dispense` / `charge:pay` / `system:admin` / `patient:booking`
- 登录方式：统一真登录（手机号 + 密码），无 dev 模拟登录开关，未登录由路由守卫跳 `/login`
- 后端：控制器方法上标注 `@PreAuthorize("hasAuthority('...')")`
- 前端：登录页（`/login` 独立路由）+ 右上角下拉（修改密码，改完强制重登）

### 就诊单状态机

```
挂号（Registration）
  ↓ 叫号（自动生成就诊单）
CREATED（草稿）
  ↓ 确单按钮（医生点击，锁定医嘱）
CONFIRMED（已确单）
  ↓ 收费员缴费
IN_PROGRESS（进行中）
  ↓ 所有医嘱执行/取消完毕（自动）
FINISHED（已完成）
```

### 门诊挂号与分诊排队

**挂号流程：**
- 患者到院 → 挂号员选择患者 + 科室 → 生成排队号（`clinical.registration`）
- 排队号按科室当日自增

**叫号流程：**
- 挂号员点击"叫下一位" → 取 WAITING 状态中排队号最小的记录
- 自动创建就诊单（`clinical.visit`），状态为 CREATED
- 挂号记录状态更新为 CALLED，关联 visitId

**排队状态：**
```
WAITING（候诊）→ CALLED（已叫号）→ 就诊完成/取消
```

**科室大屏：**
- 患者通过大屏查看当前排队进度
- SSE 实时推送叫号通知

**确单按钮**（`VisitDetailView.vue`）：
- 显示条件：草稿状态 + 有医嘱 + 有 `visit:entry` 权限
- 点击后：锁定就诊单 → 自动生成检验申请（LAB 医嘱）+ 处方（MEDICATION 医嘱）
- 幂等：已是 CONFIRMED 则直接返回

**结算按钮**：
- 显示条件：有未缴费用 + 就诊状态为 CONFIRMED 或 IN_PROGRESS
- CREATED 状态不可结算（需先确单）

**二次诊断追加**：
- 医生可在 IN_PROGRESS 状态追加新医嘱
- 追加药品/检验时自动同步到处方/检验申请（追加到现有 PENDING 单据）

### 前端架构

Vue 3 + TypeScript + Vite 5 单页应用，配 Element Plus：

- **布局：** `MainLayout.vue`——侧边栏（`el-menu`）+ 顶栏 + 主内容区
- **路由：** 挂在 MainLayout 下的嵌套路由，29 个视图，`meta.requiresAuth` + 全局守卫
- **状态：** 按业务域拆分的 Pinia store（`auth`、`visit`、`notification`、`dispatch`、`menu`、`patient`）
- **接口：** `api/http.ts` 中的 Axios 实例（带 Bearer 令牌拦截器），按业务域划分的 API 模块
- **认证：** 统一自管 JWT 真登录（手机号 + 密码），登录态持久化到 localStorage，刷新免登

### API 文档

启动后端服务后，可通过 Swagger UI 查看和测试 API：
- 直接访问：http://localhost:8101/swagger-ui.html
- 通过网关：http://localhost:8104/swagger-ui.html

所有 Controller 已添加 OpenAPI 注解（@Tag、@Operation、@Parameter）。

### 错误处理

- **后端**：`GlobalExceptionHandler` 统一捕获异常，返回标准 JSON 格式 `{timestamp, status, error, message}`，覆盖以下异常类型：
  - **400**：`MethodArgumentNotValidException`（@Valid 校验失败）、`ConstraintViolationException`（@Validated 校验失败）、`HttpMessageNotReadableException`（JSON 解析失败）、`MethodArgumentTypeMismatchException`（路径参数类型错误）、`MissingServletRequestParameterException`（缺少必填参数）
  - **401**：`BadCredentialsException`（认证失败）
  - **403**：`AccessDeniedException`（权限不足）
  - **404**：`NoHandlerFoundException`（无匹配 Handler）、`IllegalArgumentException`（业务层资源不存在）
  - **409**：`IllegalStateException`（业务状态冲突，如未缴费拦截、处方已发药）
  - **500**：`Exception`（兜底，所有未预期异常）
- **前端**：`ErrorBoundary.vue` 错误边界 + `main.ts` 全局 errorHandler + Axios 拦截器统一提示

### 输入校验约定
- Request DTO 使用 Jakarta Validation 注解（@NotNull/@NotBlank/@Pattern 等）
- Controller 方法参数标注 @Valid 触发校验
- 校验失败由 GlobalExceptionHandler 统一返回 400

### 测试约定

- JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`）+ AssertJ（`assertThat`）
- 服务测试用 `@Mock` + `@InjectMocks` 模式（不加载 Spring 上下文）
- 架构测试位于 `ArchitectureTest.java`
- 前端暂无测试

### CI（GitHub Actions）

单一 Job：在 JDK 21（Temurin）上执行 `mvn -B clean verify`，触发条件为推送到 main/master 及 PR。
