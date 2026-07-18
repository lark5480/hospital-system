# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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

# Production auth mode (frontend)
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
  |                      - platform (共享内核: audit log, messaging, job scheduling) — 非业务模块,所有模块可依赖
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
| `clinical` | visit, orders, charge, visit_read_model (CQRS), medical_record (JSONB) |
| `patient` | patient |
| `booking` | exam_package, exam_item, slot, appointment |
| `dispatch` | exam_task, queue_board |
| `pharmacy` | prescription, prescription_item |
| `lab` | requisition, result_item |
| `report` | record |
| `org` | department, staff |

ORM: MyBatis-Plus 3.5.7, `@TableName("schema.table")`, `map-underscore-to-camel-case: true`.

### Event-driven patterns

- **In-process:** Spring `ApplicationEventPublisher` for domain events within hospital-core
- **Cross-process:** RabbitMQ bridge - `VisitEventAmqpBridge` publishes `VisitCreatedEvent` to `hospital.exchange`, notification-service consumes it
- **Scheduling:** `@Scheduled` — `SlotGenerateJob` (每天 3:00 号源生成), `AppointmentCleanupJob` (每天 4:00 过期清理)

### P2 Features (已完成)

#### CQRS-lite 读模型

- **目的**: 优化就诊列表查询性能，解决 N+1 查询和内存分页问题
- **实现**: `clinical.visit_read_model` 表，写操作同一事务内同步更新
- **关键类**: `VisitReadModel`, `VisitReadModelMapper`, `VisitReadModelService`
- **查询优化**: `listPage()` 从 O(N²) 内存分页优化为 O(1) 数据库分页

#### FHIR Facade

- **目的**: 实现 FHIR R4 标准接口，支持医疗数据互联互通
- **实现**: 只读 API，手写 JSON 转换，不引入 HAPI FHIR 等重型库
- **支持资源**: Patient, Encounter, Condition, CapabilityStatement
- **API 端点**: `/fhir/Patient`, `/fhir/Encounter`, `/fhir/Condition`, `/fhir/metadata`

#### 结构化电子病历

- **目的**: 将病历从纯文本升级为结构化存储，支持 JSONB 查询
- **实现**: `clinical.medical_record` 表，JSONB 存储体格检查、诊断等半结构化数据
- **关键类**: `MedicalRecord`, `MedicalRecordMapper`, `MedicalRecordService`
- **自定义 TypeHandler**: `JsonbTypeHandler` 解决 MyBatis-Plus + PostgreSQL JSONB 兼容问题

- **In-process:** Spring `ApplicationEventPublisher` for domain events within hospital-core
- **Cross-process:** RabbitMQ bridge - `VisitEventAmqpBridge` publishes `VisitCreatedEvent` to `hospital.exchange`, notification-service consumes it
- **Scheduling:** `@Scheduled` — `SlotGenerateJob` (每天 3:00 号源生成), `AppointmentCleanupJob` (每天 4:00 过期清理)

### 统一账号体系(手机号+密码 + 多角色)

- **统一账号表**:`platform.sys_user`(phone 唯一 + BCrypt 密码),`platform.sys_user_role`(user_id × role_code 多对多)
- **多角色权限并集**:登录后 JWT 含 roles(多角色) + authorities(七权并集),菜单 = 并集可见
- 七权 RBAC: `visit:entry` / `visit:audit` / `order:execute` / `pharmacy:dispense` / `charge:pay` / `system:admin` / `patient:booking`
- Default-off: backend runs in dev mode (doctor01 自动登录)
- Enable with `VITE_AUTH_ENABLED=true` (frontend)
- Backend: `@PreAuthorize("hasAuthority('...')")` on controller methods
- Frontend: 登录页(`/login` 独立路由) + 右上角下拉(修改密码,改完强制重登)

### Frontend architecture

Vue 3 + TypeScript + Vite 5 SPA with Element Plus:

- **Layout:** `MainLayout.vue` — sidebar (`el-menu`) + header + main area
- **Routing:** Nested routes under MainLayout, 16 views, `meta.requiresAuth` + global guard
- **State:** Pinia stores per domain (`auth`, `visit`, `notification`, `dispatch`, `menu`, `patient`)
- **API:** Axios instance in `api/http.ts` with Bearer token interceptor, per-domain API modules
- **Auth:** self-managed JWT login when `VITE_AUTH_ENABLED=true`; mock user with all authorities when disabled

### Testing patterns

- JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) + AssertJ (`assertThat`)
- Service tests use `@Mock` + `@InjectMocks` pattern (no Spring context)
- ArchUnit architecture tests in `ArchitectureTest.java`
- No frontend tests yet

### CI (GitHub Actions)

Single job: `mvn -B clean verify` on JDK 21 (Temurin) for push to main/master and PRs.
