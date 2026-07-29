# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中协作时提供指引。

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
  |                      - org（科室、员工）   [10 个业务域之一]
  |                      - platform（共享内核：审计日志、消息、任务调度、统一账号）——非业务模块，所有模块可依赖
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

### 数据库：按模块划分 schema 隔离

所有表都在同一个 PostgreSQL 16 实例（`hospital` 库）中，按 schema 隔离：

| Schema | 模块 |
|---|---|
| `platform` | audit_log、sys_user、sys_user_role、role、role_authority |
| `clinical` | visit、orders、charge、registration、visit_read_model（CQRS）、medical_record（JSONB） |
| `patient` | patient（含 user_id 关联统一账号） |
| `booking` | exam_package、exam_item、slot、appointment |
| `dispatch` | exam_task、queue_board |
| `pharmacy` | prescription、prescription_item |
| `lab` | requisition、result_item |
| `report` | record |
| `org` | department、staff（含 user_id 关联统一账号） |

ORM：MyBatis-Plus 3.5.7，`@TableName("schema.table")`，`map-underscore-to-camel-case: true`。

### 事件驱动模式

- **进程内：** Spring `ApplicationEventPublisher` 处理 hospital-core 内部的领域事件
- **跨进程：** RabbitMQ 桥接——`VisitEventAmqpBridge` 把 `VisitCreatedEvent` 发布到 `hospital.exchange`，由 notification-service 消费
- **调度：** `@Scheduled`——`SlotGenerateJob`（每天 3:00 生成号源）、`AppointmentCleanupJob`（每天 4:00 清理过期预约）

### 统一账号体系（自管 JWT，手机号 + 密码）

- **统一账号表：** `platform.sys_user`（phone 唯一 + BCrypt 密码 + name + status）。
- **多角色绑定：** `platform.sys_user_role`（user_id + role_code 多对多），一个账号可同时是医生 + 患者 + 管理员。
- **自管 JWT：** 后端 `JwtTokenService` 签发/解析，前端登录换签令牌，请求带 `Authorization: Bearer`。
- 本地零配置可跑；未来接外部 IdP 只需替换登录环节（校验外部令牌 → 换签自有 JWT）。
- **登录接口：** `POST /api/auth/login?phone=xxx&password=xxx` → 返回 token + roles（多角色）+ authorities（权限并集）。
- **默认密码：** `123456`（首次登录后建议修改，从右上角下拉菜单主动触发，改密后强制重新登录）。
- **角色↔权限入库：** `platform.role` + `platform.role_authority` 表，管理员后台可调（`RoleController`）。
- **七权 RBAC：** `visit:entry`（录入）/ `visit:audit`（审核）/ `order:execute`（检查执行）/ `pharmacy:dispense`（发药）/ `charge:pay`（收费）/ `system:admin`（管理）/ `patient:booking`（体检预约）。
- **后端鉴权：** `@PreAuthorize("hasAuthority('...')")` 走 JWT 中的 authorities。
- **前端菜单过滤：** `MenuService` 按当前用户 authorities 裁剪 `MenuConfig` 菜单树（多角色 = 权限并集）。
- **分层规则**（ArchUnit 强制）：`platform.security`（基础设施层）只放纯 JWT 工具；认证编排在 `org.application.AuthService`。

### 前端架构

Vue 3 + TypeScript + Vite 5 单页应用，配 Element Plus：

- **布局：** `MainLayout.vue`——侧边栏（`el-menu`）+ 顶栏 + 主内容区
- **路由：** 挂在 MainLayout 下的嵌套路由，29 个视图，`meta.requiresAuth` + 全局守卫
- **状态：** 按业务域拆分的 Pinia store（`auth`、`visit`、`notification`、`dispatch`、`menu`、`patient`）
- **接口：** `api/http.ts` 中的 Axios 实例（带 Bearer 令牌拦截器），按业务域划分的 API 模块
- **认证：** 统一自管 JWT 真登录（手机号 + 密码），登录态持久化到 localStorage，刷新免登
- **账号自助：** 登录页（`/login` 独立路由）+ 右上角下拉（修改密码）+ 忘记密码引导（联系管理员重置）

### API 文档

启动后端服务后，可通过 Swagger UI 查看和测试 API：
- 直接访问：http://localhost:8101/swagger-ui.html
- 通过网关：http://localhost:8104/swagger-ui.html

所有 Controller 已添加 OpenAPI 注解（@Tag、@Operation、@Parameter）。

### 错误处理

- **后端**：`GlobalExceptionHandler` 统一捕获异常，返回标准 JSON 格式 `{timestamp, status, error, message}`
- **前端**：`ErrorBoundary.vue` 错误边界 + `main.ts` 全局 errorHandler + Axios 拦截器统一提示

### 测试约定

- JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`）+ AssertJ（`assertThat`）
- 服务测试用 `@Mock` + `@InjectMocks` 模式（不加载 Spring 上下文）
- 架构测试位于 `ArchitectureTest.java`
- 前端暂无测试

### CI（GitHub Actions）

单一 Job：在 JDK 21（Temurin）上执行 `mvn -B clean verify`，触发条件为推送到 main/master 及 PR。
