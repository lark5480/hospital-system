# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Infrastructure (start all middleware)
docker compose up -d

# Build all backend modules (skip tests for speed)
mvn clean install -DskipTests

# Build & test (CI pipeline)
mvn -B clean verify

# Run a single test class
mvn -pl hospital-core -Dtest=BookingServiceTest test

# Run architecture boundary tests (ArchUnit) only
mvn -pl hospital-core -Dtest=ArchitectureTest test

# Run backend service (4 terminals)
mvn -pl hospital-core spring-boot:run                          # :8101
mvn -pl hospital-notification-service spring-boot:run          # :8102
mvn -pl hospital-file-service spring-boot:run                  # :8103
mvn -pl hospital-gateway spring-boot:run                       # :8104

# Frontend
cd hospital-web
npm install
npm run dev              # :5173 with Vite proxy to gateway
npm run build            # type-check + production build
npm run type-check       # vue-tsc type checking only

# 生产登录态(可选,默认 dev 模式直连)
echo "VITE_AUTH_ENABLED=true" > hospital-web/.env.local
```

## Project Architecture

**Hybrid pattern:** Modular monolith core + 2 extracted services behind an API Gateway.

```
Browser (hospital-web :5173)
  | /api (Vite proxy)
  v
API Gateway (hospital-gateway :8104)
  |-- /api/core/**   -> hospital-core (:8101)   [Modular monolith]
  |                      - clinical (visits, orders, charges)
  |                      - pharmacy (prescriptions, dispensing)
  |                      - lab (requisitions, results)
  |                      - booking (appointments, packages, slots)
  |                      - dispatch (queue engine)
  |                      - patient (registry)
  |                      - report (medical records)
  |                      - iam (menu/RBAC)
  |                      - org (department, staff)   [9 业务域之一]
  |                      - platform (共享内核: audit log, messaging, job scheduling, 统一账号) — 非业务模块,所有模块可依赖
  |-- /api/notify/**  -> notification-service (:8102)  [Event consumer]
  |-- /api/files/**   -> file-service (:8103)           [MinIO wrapper]
```

### Backend Modules (Maven multi-module)

| Module | Role |
|---|---|
| `hospital-core` | Modular monolith — 9 domain modules inside, each with DDD layers |
| `hospital-gateway` | Spring Cloud Gateway — route `/api/core/**`, `/api/notify/**`, `/api/files/**` |
| `hospital-notification-service` | RabbitMQ consumer — listens for `VisitCreatedEvent` |
| `hospital-file-service` | MinIO wrapper — file upload/download |

### Per-module DDD structure (inside hospital-core)

Each domain module follows 4 layers:

```
clinical/          (example module)
  ├── domain/         Entities, value objects (Visit, Order, Charge)
  ├── application/    Service classes (VisitService)
  ├── infrastructure/ MyBatis-Plus Mappers (VisitMapper extends BaseMapper<Visit>)
  └── api/            REST Controllers (VisitController)
```

Enforced by ArchUnit in CI (ArchitectureTest): `api` → `application` → `domain` ← `infrastructure`.

### Module isolation rules

- `clinical` must NOT depend on `pharmacy`, `lab`, `operation`, `integration`
- `platform` is the shared kernel — all modules may depend on it
- ArchUnit rules run during `mvn verify` and break the build on violation

### Database: schema-per-module isolation

All tables live in one PostgreSQL 16 instance (`hospital` db), separated by schema:

| Schema | Modules |
|---|---|
| `platform` | audit_log, sys_user, sys_user_role, role, role_authority |
| `clinical` | visit, orders, charge |
| `patient` | patient(含 user_id 关联统一账号) |
| `booking` | exam_package, exam_item, slot, appointment |
| `dispatch` | exam_task, queue_board |
| `pharmacy` | prescription, prescription_item |
| `lab` | requisition, result_item |
| `report` | record |
| `org` | department, staff(含 user_id 关联统一账号) |

ORM: MyBatis-Plus 3.5.7, `@TableName("schema.table")`, `map-underscore-to-camel-case: true`.

### Event-driven patterns

- **In-process:** Spring `ApplicationEventPublisher` for domain events within hospital-core
- **Cross-process:** RabbitMQ bridge - `VisitEventAmqpBridge` publishes `VisitCreatedEvent` to `hospital.exchange`, notification-service consumes it
- **Scheduling:** `@Scheduled` — `SlotGenerateJob` (每天 3:00 号源生成), `AppointmentCleanupJob` (每天 4:00 过期清理)

### 统一账号体系(自管 JWT,手机号+密码)

- **统一账号表**:`platform.sys_user`(phone 唯一 + BCrypt 密码 + name + status)。
- **多角色绑定**:`platform.sys_user_role`(user_id + role_code 多对多),一个账号可同时是医生+患者+管理员。
- **自管 JWT**:后端 `JwtTokenService` 签发/解析,前端登录换签 token,请求带 `Authorization: Bearer`。
- 本地零配置可跑;未来接外部 IdP 只需替换 login 环节(校验外部 token → 换签自有 JWT)。
- **登录接口**:`POST /api/auth/login?phone=xxx&password=xxx` → 返回 token + roles(多角色) + authorities(权限并集)。
- **默认密码**:`123456`(首次登录后建议修改,从右上角下拉菜单主动触发,改密后强制重新登录)。
- **角色↔权限入库**:`platform.role` + `platform.role_authority` 表,管理员后台可调(`RoleController`)。
- **七权 RBAC**:`visit:entry`(录入) / `visit:audit`(审核) / `order:execute`(检查执行) / `pharmacy:dispense`(发药) / `charge:pay`(收费) / `system:admin`(管理) / `patient:booking`(体检预约)。
- **后端鉴权**:`@PreAuthorize("hasAuthority('...')")` 走 JWT 中的 authorities。
- **前端菜单过滤**:`MenuService` 按当前用户 authorities 裁剪 `MenuConfig` 菜单树(多角色 = 权限并集)。
- **分层规则**(ArchUnit 强制):`platform.security`(基础设施层)只放纯 JWT 工具;认证编排在 `org.application.AuthService`。

### Frontend architecture

Vue 3 + TypeScript + Vite 5 SPA with Element Plus:

- **Layout:** `MainLayout.vue` — sidebar (`el-menu`) + header + main area
- **Routing:** Nested routes under MainLayout, 16 views, `meta.requiresAuth` + global guard
- **State:** Pinia stores per domain (`auth`, `visit`, `notification`, `dispatch`, `menu`, `patient`)
- **API:** Axios instance in `api/http.ts` with Bearer token interceptor, per-domain API modules
- **Auth:** self-managed JWT login(手机号+密码) when `VITE_AUTH_ENABLED=true`; mock doctor01 with all authorities when disabled
- **账号自助:** 登录页(`/login` 独立路由) + 右上角下拉(修改密码) + 忘记密码引导(联系管理员重置)

### Testing patterns

- JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) + AssertJ (`assertThat`)
- Service tests use `@Mock` + `@InjectMocks` pattern (no Spring context)
- ArchUnit architecture tests in `ArchitectureTest.java`
- No frontend tests yet

### CI (GitHub Actions)

Single job: `mvn -B clean verify` on JDK 21 (Temurin) for push to main/master and PRs.
