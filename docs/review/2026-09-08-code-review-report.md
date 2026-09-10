# hospital-system 多 Agent 交叉质询代码审查报告

- **审查日期**：2026-09-08
- **审查范围**：`hospital-core`(159 生产 Java 文件) / `hospital-notification-service`(17) / `hospital-file-service`(4) / `hospital-gateway`(2) / `hospital-web`(41 ts + 36 vue) / `db/schema.sql` / `docker-compose.yml` / `pom.xml` / `.github/workflows/ci.yml`
- **参与 Agent**：SECURITY（安全）、PERFORMANCE（性能）、TESTING（测试质量）
- **流程**：第 1 轮三方独立审查 → 第 2 轮交叉质询（反驳 / 降级 / 升级 / 补充 / 自我修正 / 预判辩护）→ 第 3 轮主席收敛
- **产出**：61 条问题（6 Critical / 22 High / 26 Medium / 7 Low），含 8 条质询后新增、9 条质询后改级
- **实施状态（2026-09-10 更新）**：P0 + P1 + P2 已全部落盘 —— 原 61 条中 **54 项已修复、1 项已缓解、6 项部分修复**；另在复核中发现 **2 条审核漏项（R-62 Critical、R-63 High）尚未修复**。详见下方「实施状态总览」与「遗留」
- **定级标准**：
  - **Critical**：可直接导致批量敏感数据泄露 / 权限完全失守，或线上必然不可用
  - **High**：需要低门槛前置条件即可造成实质损害，或数据量到 10 万级必然劣化到不可用
  - **Medium**：在特定条件叠加下才触发，或属于可被利用的放大器 / 工程化缺失
  - **Low**：最佳实践偏离、后门代码路径、局部体验问题

---

## 实施状态总览（2026-09-09 更新）

### 已修复（Done）

| 编号 | 修复内容 | 主要文件 |
|---|---|---|
| R-01 | 删除 JWT 硬编码默认密钥；启动期 fail-fast（prod 抛异常 / 非 prod 生成随机密钥并告警）；`APP_JWT_SECRET` 注入位 | `JwtTokenService`、`application.yml` |
| R-02 | `/fhir/**` 移出 permitAll；4 个 FHIR Controller 加 `@PreAuthorize`；**无过滤参数一律 400**（刻意不用分页——分页照样能逐页拖库）；身份证出参脱敏 | `SecurityConfig`、`Fhir*Controller`、`FhirApiTest` |
| R-03 | 新增 `InternalTokenFilter`（`X-Internal-Token`）；`patientId` 改必填、`owner==null` 一律 403；上传正则 + 20MB 限制 + **禁止覆盖(409)**；`Content-Disposition` 改用 `ContentDisposition` 构造 | `FileController`、file-service `SecurityConfig`/`application.yml`、`FileServiceClient` |
| R-07 | 患者列表分页下推（上限 500）；新增 `/api/patient/names?ids=`；出参身份证脱敏；**姓名/身份证/手机号收敛为仅 admin 可改**；`update` 补 `@AuditLog` | `PatientController`、`PatientService`、`PatientsView.vue` |
| R-08 | 病历/就诊/报告读接口补 `@PreAuthorize` + 归属校验（患者强制覆盖为本人 patientId）；C 端读接口追加 `patient:booking` | `VisitController`、`MedicalRecordController`、`ReportController`、`VisitService` |
| R-09 | `create`→`visit:entry`、`publish`→`visit:audit\|system:admin`；`doctorId` 从 `CreateReportRequest` 删除、改服务端解析 | `ReportController`、`CreateReportRequest` |
| R-10 | 登录响应返回 `mustChangePassword`（密码仍等于默认口令即置真，**未新增数据库列**）；密码策略 8~64 位 + 字母数字 + 弱口令黑名单 + 不得与旧密码相同；删除"旧密码为空跳过校验"分支；新建强制改密页 + 路由守卫 + 前端即时校验 | `PasswordController`、`AuthService`、`ChangePasswordView.vue`、`ChangePasswordDialog.vue`、`router/index.ts` |
| R-12 | 登录改 `@RequestBody`（密码不再进 URL query）；新增 `LoginAttemptService`（5 次锁 15 分钟 + IP 限流 + 惰性清理）；429 单独提示 | `AuthController`、`LoginView.vue`、`api/auth.ts`、`http.ts` |
| R-13 | SSE `0L`→60s + 15s 心跳 + 500/300 连接上限 + `catch(Exception)`+`finally` 清理；6 处广播改 `afterCommit` 移出事务；subscribe 加 `isAuthenticated()` | `DispatchSseController`、`DispatchService`、notification `NotificationController` |
| R-14 | `Set<SseEmitter>` → `Map<station, Set>`，**station 过滤真正生效**；未指定 station 的订阅者仍收全量（保持兼容） | `DispatchSseController` |
| R-22 | `RestTemplate` connect 3s / read 15s；`reportPdfExecutor` 补 `CallerRunsPolicy` | `AsyncConfig`、`FileServiceClient` |
| R-30 | `objectName` 正则校验；20MB 限制；同名对象 409 禁止覆盖（防报告投毒）；上传响应体 `Map.of` NPE 修复 | `FileController`、file-service `application.yml` |
| R-57 | 删除 `AuthService` 空密码免密分支；`PasswordController` 对称分支一并删除 | `AuthService`、`PasswordController` |
| R-39 | element-plus 按需引入：vite 加 `unplugin-vue-components` + `ElementPlusResolver({ importStyle: false })`（保留全量 CSS —— 主题映射层 `styles/element.css` 依赖 `index.css` 的加载顺序，且 `ElMessage` 这类显式 import 的函数式 API 不走 resolver）。**必须显式 `app.use(ElLoading)`**：该插件只解析 `<el-xxx>` 标签、**不解析 `v-loading` 指令**，实测不注册会让全仓 20 处 `v-loading` 静默失效。新增解析回归测试（build 成功 ≠ 组件可用） | `vite.config.ts`、`main.ts`、新增 `elementPlusResolve.spec.ts` |
| R-40 | 轮询加页面可见性感知：新增 `utils/polling.ts`（`createVisibilityAwarePoller`，隐藏即暂停、可见即拉取并恢复、`start()` 幂等），`OutpatientScreenView` / `PatientMyQueueView` 共用并清理监听。核实两页数据与看板 SSE **不重叠**，故保留轮询 | 新增 `utils/polling.ts`、两个 View |
| R-49 | 消除集成测试的种子数据耦合：`FhirApiTest` / `VisitReadModelTest` / `MedicalRecordTest` 改为用 `JdbcTemplate` 自造数据 + 自清理，`total==1` 改包含式断言 | 三个测试类 |
| R-50 | `DataInitializer` 补测试（纯 Mockito）：水位命中跳过重建 / 水位缺失触发重建 / 无未迁移行时不下发全表查询 / **冲突时不得执行任何 DELETE**（R-58 护栏） | 新增 `DataInitializerTest` |
| R-56 | token 存储窗口收窄：`localStorage` → `sessionStorage`，storage 访问加不可用兜底（原写法在禁用 Storage 时会在 import 阶段抛错导致白屏），旧 localStorage 键自动清理；401 兜底改为一次性清空内存态 + 缓存（原先残留 `roles/authorities`，会出现"已登出仍渲染管理员菜单"） | `stores/auth.ts`、`api/http.ts` |
| R-59 | `VisitStatus` 参数化矩阵测试。**实测纠正**：`of(null)` 静默降级 `CREATED`，但 `of("")` / `of("NOT_A_STATUS")` 会抛 `IllegalArgumentException`（并非全部静默），且大小写敏感；已按实际行为固化并标注不一致 | 新增 `VisitStatusTest` |
| R-60 | PDF 字体路径跨平台：路径配置化为 `app.report.pdf.font-path`，并内置 Windows/Linux/macOS 候选列表，全部缺失时沿用降级 + 告警；新增 PDF 产物断言（`%PDF-` 魔数、`%%EOF`），并在强制无字体环境下验证降级路径 | `ReportPdfGenerator`、`application.yml`、新增 `ReportPdfGeneratorTest` |
| R-61 | 前端零测试：引入 vitest + jsdom + `@vue/test-utils`，`vitest.config.ts` **复用 `vite.config.ts` 的插件**（否则按需引入类问题在测试里永远暴露不出来），19 个用例覆盖 polling / auth 存储与权限 / 密码校验 / element-plus 解析 | `package.json`、新增 `vitest.config.ts` 与 4 个 spec |
| R-15 | 启动期全量重建：新增 `platform.meta` 水位表；`initAll()` 加版本守卫 + 主键游标分批；`migrateUsers()` 先 `count(user_id IS NULL)` 短路。实测启动步进总耗时 **36ms** | `DataInitializer`、`VisitReadModelService`、`schema.sql` |
| R-16 | 读模型写放大：新增一条聚合 SQL（`VisitReadModelMapper.selectAggregate`）取代 2 次全量 selectList；名称解析走 `NameCache`；`addOrder/editOrder/cancelOrder` 复用刷新结果，不再重复 `getDetail()`。**异步刷新主动放弃**（会破坏 `VisitReadModelTest` 的事务可见性），已在 javadoc 写明 | `VisitReadModelService`、`VisitReadModelMapper`、`VisitService` |
| R-19 | 名称解析 N+1：新增 `NameCache`（TTL 5min / 上限 5000 / 惰性清理），lab / pharmacy / report 列表改为批量解析（一次 `WHERE id IN`），出参结构不变 | 新增 `platform/support/NameCache`、`LabService`、`PrescriptionService`、`ReportService` |
| R-20 | 搜索索引失效：引入 `pg_trgm` 扩展 + 5 个 GIN 三元组索引，使 `LIKE '%kw%'` 也能走索引，**Java 查询无需改动**；注释说明中文场景后续应换 `zhparser` + `tsvector` | `db/schema.sql` |
| R-31 | 审计异常短路：改为 `try/finally` 包裹，失败操作同样留痕（detail 追加 `FAILED: 异常类型`）；审计写入失败不再影响业务流程；detail 超长安全截断 | `AuditLogAspect` |
| R-32 | 错误信息直出：`include-message` / `include-stacktrace` 由 `always` 改为 `never`（业务中文提示由 `GlobalExceptionHandler` 显式构造，不受影响） | core `application.yml` |
| R-35 | 通知服务 CORS：`allowedOriginPatterns("*")` + `allowCredentials(true)` 的危险组合，改为可配置 `app.cors.allowed-origins`，默认仅本机开发源（与 core 口径一致） | `CorsConfig`、notification `application.yml` |
| R-36 | 中间件暴露：compose 中 PG/Redis/MinIO/RabbitMQ 全部端口改绑 `127.0.0.1`（原先内网任何人可用默认口令直连拖库）；新增 `.env.example` 列出全部需覆盖的密钥 | `docker-compose.yml`、新增 `.env.example` |
| R-37 | 看板全量加载：`board()` 下推状态白名单（剔除 DONE）+ `CASE WHEN` 排序下推 SQL，删除 Java `rows.sort`；`listActiveStations()` 改为单条 `SELECT DISTINCT`；补 `idx_board_status_seq`。**刻意不加"当日"过滤**——会让无新预约时的看板空白（体验回退），剔除 DONE 已解决体积问题 | `DispatchService`、`QueueBoardMapper`、`schema.sql` |
| R-38 | `callNext` 的 N+1（单 station 积压 200 人时约 401 次 SQL）改单条 SQL（`NOT EXISTS` + `MIN(seq)` + `ORDER BY seq LIMIT 1`）；`max(seq)` 改聚合查询；挂号排队号由 `selectCount` 改 `MAX(queue_no)+1` | `DispatchService`、`ExamTaskMapper`、`RegistrationService` |
| R-41 | PDF 字体不再每次重解析：改为进程级共享字体解析器 + 缺失降级标记（原每次 `exists()` + 解析约 20MB 的 ttc）。同步阻塞请求线程的问题保留并留 `TODO(P2)`（属接口语义变更） | `ReportPdfGenerator` |
| R-42 | `book()` 取价提到 insert 之前（同一行不再两次写）；`ensureSlotsExist` 由 14 次 `selectCount` + 14 次 insert 改为 1 次范围查询 + 1 条批量 INSERT；过期预约清理改批量 UPDATE + 批量释放号源 | `BookingService`、`SlotMapper` |
| R-43 | `listPage` 的 `inSql` 字符串拼接改为 `apply(..., {0})` 参数绑定（保留单条 SQL 与 O(1) 分页语义）；`orders.execution_dept_id` 索引已在 R-04 补齐 | `VisitService` |
| R-44 | 审计写入移出业务线程：新增 `auditLogExecutor`（core 1/max 2/queue 1000/守护线程/`CallerRunsPolicy`）；提交失败或执行器不可用时**降级为同步写入**，审计绝不静默丢失；R-31 的 `try/finally` 语义保持 | `AsyncConfig`、`AuditLogAspect` |
| R-45 | BCrypt 强度 10 → 12（前置的登录失败锁定与 IP 限流已落地）。说明：校验代价由哈希内记录的 cost 决定，既有账号登录速度不变，仅新建/改密的编码变慢 | `SecurityConfig` |
| R-15 | 启动期全量重建：新增 `platform.meta` 水位表；`initAll()` 加版本守卫 + 主键游标分批（每批 1000）；`migrateUsers()` 先 `count(user_id IS NULL)` 短路，为 0 则整段跳过；`run()` 逐步打印耗时 | 新增 `platform.meta` 表、`DataInitializer`、`VisitReadModelService` |
| R-25 | 重复预约幂等：`book()` 占号前校验同患者同号源已有 BOOKED 预约 → 409。**不加数据库唯一索引**（`sql.init.mode=always` 会让存量重复行直接导致启动失败），并发双写的彻底解决留 P3 | `BookingService` |
| R-46 | 防超卖并发用例：`BookingConcurrencyTest` 用真实 PG + 8 线程 + `CountDownLatch` 同放，断言成功数==1、`slot.booked==1`、BOOKED 预约==1；**刻意不加 `@Transactional`**（线程共享事务就测不出并发），`@AfterEach` 清理自造数据 | 新增 `BookingConcurrencyTest` |
| R-48 | ArchUnit 守护状态机旁路：新增规则「`*Service`（除 `VisitService`）不得调用 `Visit.setStatus`」，白名单为 `VisitService` 的建单初始化（`CREATED`，此时无前序状态）；另加 `visitStateMachineGuardIsArmed` 防止规则对空集合假通过。**已注明局限**：ArchUnit 是类粒度，`VisitService` 内部新增的旁路拦不住 | `ArchitectureTest` |
| R-51 | `JsonbTypeHandler` 测试：9 个用例覆盖合法 JSON / null / 空串 / `"null"` / `{}` / `[]` / 非法 JSON；把「非法 JSON 原样返回字符串」固化为显式契约并标注为静默降级风险 | 新增 `JsonbTypeHandlerTest` |
| R-52 | 分页边界：核实 `pageNum=0` 会算出**负 OFFSET**（PG 直接报错）、`pageSize<=0` → `LIMIT 0`、超大值 → 全量拉取；已加防御式校验（pageNum≥1、pageSize 1~500）并补 5 个边界用例 | `VisitService`、新增 `VisitServiceListPageTest` |
| R-53 | 补 `finishVisit(force)` 退款与 `pay` 事件断言。**实测发现与用例预期不符**：存在 UNPAID 费用时 `finishVisit` 在 force 分支之前就抛 `IllegalStateException("存在未缴费用…")`，既不删除也未缴费、不作废医嘱；已按实际行为断言并注释说明（未改主源码） | `VisitServiceTest` |
| R-54 | `MenuServiceTest` 污染 `SecurityContextHolder`：补 `@BeforeEach/@AfterEach` 清理。**实测纠正**：注解声称「匿名返回完整菜单」并不成立（匿名时 authority 为空，仍会按 `menu_authority` 裁剪），已改为固化真实语义的用例 | `MenuServiceTest` |
| R-58 | 启动期破坏性 DELETE：`ensureStaffPhoneUnique()` 改为「先探测冲突 → `log.error` 打印冲突 phone 清单并跳过建约束」，**不再静默删除员工档案**（选择"跳过"而非"中止启动"：该 UNIQUE 是额外加固而非硬依赖，不应把局部数据问题放大成全站不可用） | `DataInitializer` |
| 决策 4B | **由网关注入 `X-Internal-Token`**（`AddRequestHeader`）——令牌只存在于服务端，浏览器永不接触，前端零改动；file-service 在 prod 未配置令牌即拒绝启动（与 R-01 对称） | gateway `application.yml`、`InternalTokenFilter`、file-service `SecurityConfig` |
| 决策 5 | **改密/重置即吊销该用户全部 token**（新增 `TokenRevocationService`，Redis 用户维度，TTL 与 token 对齐）；JWT TTL 8h→4h；`issue()` 加 jti 为单设备登出预留 | `TokenRevocationService`(新)、`JwtAuthFilter`、`JwtTokenService`、`PasswordController`、`ChangePasswordView.vue` |

### 部分修复

| 编号 | 已完成 | 未完成 |
|---|---|---|
| R-11 | core `SecurityConfig` 收口；file-service 加内部令牌 | **notification-service 仍 `permitAll()`**（无 JWT 基础设施，加强制鉴权会打断前端 SSE），仅加注释排 P1 |
| R-21 | 收费记录（charge）改为单条批量 INSERT，N 次往返降为 1 次 | 医嘱（orders）保持逐条：`charge.order_id` 需要医嘱的自增主键回填，而 `INSERT ... VALUES(),() RETURNING id` 无法通过 MyBatis 可靠按序取回多 id，收益不抵风险。已留 `TODO(P3)`（`reWriteBatchedInserts=true` + `ExecutorType.BATCH`） |
| R-33 | `/actuator/**` 移出 permitAll | Swagger / OpenAPI 仍放行（开发依赖，生产关闭未做） |
| R-34 | 改密 / 重置吊销已实现 | `?token=` query 回退未动（SSE 依赖 `EventSource`） |
| R-47 | `book()` 的并发与幂等已补（含真实 PG 并发用例） | 号源生成 / 过期清理 / 状态机用例仍缺 |

### 附带修复（原报告未列项）

| 问题 | 说明 |
|---|---|
| **患者编辑表单无法保存** | 列表出参身份证已脱敏（含 `*`），回填表单后过不了正则校验 → **任何人都保存不了**。`openEdit` 改为脱敏值置空 + placeholder 提示"留空表示不修改" |
| `StaffView.vue` 既有编译错误 | `patientApi.resetPassword` → `resetPatientPassword`；`Staff` 类型补 `userId` |
| 403 无统一提示 | `http.ts` 补 403 中文提示；401 时不再重复跳转登录页 |

### 遗留（本轮新发现的后续项，均非原报告条目）

- **R-62【Critical，审核漏项】文件下载的归属校验可被自身列表接口绕过，且网关把内部令牌无差别发给所有调用者**。
  - **证据链**：
    1. `hospital-gateway` 的 `SecurityConfig` 是 `@Profile("!iam")` + `anyExchange().permitAll()`，而**全仓不存在 `application-iam.yml`**、`application.yml` 里也没有 `spring.profiles.active=iam` → 网关**实际永远**运行在"全部放行"分支（`SecurityConfig.java:16-24`）；
    2. 我为 R-03 加的网关过滤器是 `AddRequestHeader=X-Internal-Token, ...`（`gateway/application.yml` 的 `hospital-file` 路由），它对**所有**匹配 `/api/files/**` 的请求注入令牌，**不区分调用者身份** → 在默认部署下"内部令牌"不构成任何安全边界，只防"绕过网关直连 8103"；
    3. `FileController.list()` / `mine()` 的 `patientId` 仍是 `required = false`（`FileController.java:166-178`），**不传就返回全部对象**，且 `FileMeta` 里带着每个对象的 `patientId`（`FileController.java:180-199`）；
    4. `download()` 的归属校验是"调用方自报的 `patientId` vs 对象元数据"（`FileController.java:140-146`）。
  - **利用步骤**：匿名 → `GET /api/files`（不带任何参数）拿到全部 `objectName` + `patientId` → 带着该 `patientId` 请求 `GET /api/files/{objectName}?patientId=<上一步拿到的>` → 校验通过 → 拿到患者报告 PDF。
  - **后果**：**未认证用户可枚举并下载全部患者报告。** 我原先给 R-03 定"已修复"是过度乐观 —— 单看 `download` 的改动是对的，但 `list` 把校验所需的"钥匙"（patientId ↔ objectName 的对应关系）直接送了出去，纵深防御等于没有。
  - **修复方向（即原「决策 4 方案 A」，性质从"锦上添花"上调为"必须做"）**：撤掉网关 `/api/files/**` 路由，upload / list / download 一律改由 core 代理（复用已有的 `FileServiceClient` 与 `PatientController.downloadReport` 的归属校验），让 file-service 只在内网可达；同时 `list()` 的 `patientId` 改为必填。
  - **同源简化方案（若暂不做代理）**：`FileController.list/mine` 的 `patientId` 改必填（最小止血，但令牌仍无差别发放，边界依旧不存在）。
- **R-63【High，审核漏项】`VisitService.listExams()` 循环内逐行 `visitMapper.selectById`**（`VisitService.java:781-787`）：先全量加载 EXAM 医嘱，再对每条回查就诊/患者/医生。属 R-19 同类 N+1，但位置不在 R-19 列的三个类里，故未被覆盖。修法与 R-19 一致（批量 `selectBatchIds` + 分组）。
- **R-21 医嘱批量写入**（部分完成）：收费记录已批量化，医嘱因需回填自增主键给 `charge.order_id` 而保持逐条，已留 `TODO(P3)`
- **R-39 暴露的类型债**：开启按需引入后若生成 `components.d.ts`，`vue-tsc` 会立刻暴露 **34 个既有类型问题**（`el-table` 作用域插槽的 row 被推断为 `DefaultRow`、`el-tag :type` 传入了含空串的联合类型）。本轮为守住"type-check 0 错误"基线关闭了 dts 生成；修完这 34 处即可打开，换取组件级类型安全
- **R-56 的行为变化**：改 sessionStorage 后**新开标签页不再共享登录态**（单标签刷新仍免登）。这是收窄 XSS 窗口的代价，两全需 httpOnly Cookie + CSRF（P3）
- **R-60 的运维项**：容器 / CI（ubuntu）无中文字体，PDF 中文会走降级（方框）。建议镜像挂载字体或设置 `PDF_FONT_PATH`
- **R-47 未覆盖部分**：`BookingService` 的号源生成 / 过期清理 / 状态机用例仍缺（只补了并发与幂等）
- **决策 4 方案 A（已上调为必须做）**：见上方 R-62 —— 它不只是"让 file-service 退到内网"的治本优化，而是关闭"匿名枚举 + 下载全部患者报告"这条通路的**唯一有效手段**（方案 B 的网关注入令牌对匿名调用者同样生效，起不到身份区分作用）
- **决策 1 结论**：不开放 `/visits/page`、`/reports/list`、`/reports/type` 给患者，也不新建 `/mine`。C 端能力一律走 `PatientController` 下自带归属校验的专用端点（`/api/patient/reports` 等），已可满足现有 `patient/*` 全部页面

### 新增运维依赖（部署清单必须同步）

1. `APP_JWT_SECRET`：≥32 字节随机串，未注入时 prod 启动失败
2. `FILE_INTERNAL_TOKEN`：core 与 file-service 必须一致，未注入时 file-service 在 prod 下启动失败
   - **网关侧默认值必须非空**：`AddRequestHeader` 绑定 `NameValueConfig`，value 为空会让网关启动失败
     （`Binding to target ... Property: .value Reason: 不能为空`）。故网关配置为
     `${FILE_INTERNAL_TOKEN:dev-no-token}`，不能写成 `${FILE_INTERNAL_TOKEN:}`。已由
     `GatewayDefaultTokenContextTest` 守护该回归
3. **Redis 进入认证关键路径**：`TokenRevocationService` 默认 fail-closed，Redis 不可用会拒绝全部请求（`app.jwt.revocation-fail-open=true` 可切为放行，但削弱吊销语义）

---

## 一、结论摘要

| 维度 | 最关键结论 |
|---|---|
| 安全 | 认证体系是"纸糊的"：JWT 密钥硬编码在源码（可自签 `system:admin`），`/fhir/**` 与 file-service 完全匿名，106 个端点中 51 处有 `@PreAuthorize`，而 `PatientController`/`ReportController`/`Fhir*` 三个类为 0；叠加全院统一弱口令 `123456`，攻击者获取一个有效 token 的成本极低 |
| 性能 | 全系统没有一条针对业务基表外键的二级索引（27 张表仅 2 张有索引），且 `VisitService`/`PatientService` 大量使用 `selectList(null)` 全表拉取后在 Java 内存过滤——**加索引也救不了未下推 WHERE 的写法**；叠加 Hikari 默认 10 连接，少量并发即可打满 |
| 测试 | 18 个测试类 / 101 个 `@Test` 全部集中在 `hospital-core`，另外 3 个模块 23 个文件零测试，19/23 的 Controller 无契约测试；**全仓无 JaCoCo，"缺口有多大"本身不可度量**；金额计算（真金白银）零 BigDecimal 边界断言 |

**三者不是孤立的**：安全修复与性能修复同源（补归属校验时会顺带把全表扫描下推成带 WHERE 的查询）；性能债是可被利用的 DoS 面（全表扫描在写事务内 → 持锁 → 连接池耗尽）；测试缺口决定了前两类问题能否被拦住。

---

## 二、Critical 问题（6 条）

> **状态**：R-01 ✅ ｜ R-02 ✅ ｜ R-03 ⚠️ **降级为部分修复**（详见 R-62）｜ R-04 ✅（47 条索引）｜ R-05 ✅（全表扫描下推 WHERE）｜ R-06 ✅（金额校验 + 精度）—— **5 项完成、1 项部分修复**

### R-01 JWT 签名密钥硬编码为公开默认值，可自签任意身份令牌
- **维度** 安全（SEC-01）｜**共识** 三方无异议
- **位置** `hospital-core/.../platform/infrastructure/JwtTokenService.java:33`
- **证据**
```java
@Value("${app.jwt.secret:hospital-system-dev-secret-key-must-be-at-least-32-bytes-long}")
private String secret;
```
  全仓 `application*.yml` 与 `docker-compose.yml` **均未出现** `app.jwt.secret`，即默认值就是实际生效值；`issue()`(`:55-64`) 把 `roles`/`authorities` 直接写入 payload，`parse()`(`:68-78`) 无 jti、无吊销、无黑名单。
- **影响** 用源码里这串公开密钥自签 `authorities=["system:admin"]` 的 JWT（TTL 8h），可直接调用建角色、改员工、重置密码、读全量 PHI 的管理接口，等同完全接管 HIS。
- **建议** ① 删除默认值改 `@Value("${app.jwt.secret}")`；② 启动期 fail-fast：密钥长度 < 32 或命中该 dev 串即抛异常（见 C-1 测试项）；③ 生产用环境变量/KMS 注入；④ 增加 `jti` + Redis 白名单支持吊销，改密时递增 `tokenVersion`。

### R-02 `/fhir/**` 完全匿名放行，泄露全院患者身份证 / 手机号 / 诊断
- **维度** 安全（SEC-02）｜**共识** 三方无异议
- **位置** `platform/security/SecurityConfig.java:70`；`fhir/api/FhirPatientController.java:40`；`fhir/api/FhirConditionController.java:43`
- **证据**
```java
.requestMatchers("/api/auth/**", "/actuator/**", "/fhir/**",
        "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").permitAll()
```
  不传参数时 `patientService.list()` / `visitService.listAll()` 返回**全量**；`PatientConverter.java:23-27` 把 `id_card` 写入 `identifier`。网关 `application.yml:56-59` 还显式把 `/fhir/**` 暴露到 8104。
- **影响** 一次 `curl /fhir/Patient` 拿到全院患者姓名 + 身份证 + 手机号 + 出生日期；`/fhir/Condition` 拿到全量诊断。属敏感个人信息批量泄露。
- **建议** ① 从 `permitAll()` 移除 `/fhir/**`；② 三个 Controller 加 `@PreAuthorize("hasAnyAuthority('fhir:read','system:admin')")`；③ 无过滤参数时禁止全量返回，强制分页；④ `PatientConverter` 增加身份证脱敏分支。

### R-03 file-service 全量放行 + 下载归属校验可被"省略参数"绕过
- **维度** 安全（SEC-03）｜**共识** SEC 辩护后维持，无人反驳
- **位置** `hospital-file-service/.../config/SecurityConfig.java:20`；`api/FileController.java:94-114`
- **证据**
```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
...
if (patientId != null) {                    // 不传 → 归属校验整段跳过
    String owner = stat.userMetadata().get("patientid");
    if (owner != null && !owner.equals(String.valueOf(patientId))) return 403;
}
```
- **关于"内网隔离后应降级"的裁决**：不成立。① `docker-compose.yml` 只编排中间件，应用是本地 `spring-boot:run` 绑定宿主机 8103；② 网关 `application.yml:32-35` 显式为 `/api/files/**` 建对外路由，而网关 `SecurityConfig` 是 `@Profile("!iam")` + `permitAll()`，**仓库中不存在 `application-iam.yml`**，permitAll 就是唯一运行态；③ 即使纵深防御生效，省略一个参数即可绕过。
- **影响** 匿名下载任意患者体检报告 PDF（含姓名、身份证、检验结论），遍历 id 可批量拖库。
- **建议** ① `patientId` 改必填且 `owner == null` 时拒绝匿名；② file-service `SecurityConfig` 改默认拒绝或 `oauth2ResourceServer().jwt()`；③ 8101/8102/8103 不映射宿主机端口。

### R-04 热路径外键 / 过滤列无任何二级索引
- **维度** 性能（PERF-01）｜**共识** 三方一致，但**措辞已修正**
- **位置** `hospital-core/src/main/resources/db/schema.sql`
- **修正说明** 原始结论"全部表零索引"被三方共同推翻：实际 `schema.sql:300-304` 读模型有 5 条索引、`:1042-1044` 病历有 3 条（含 JSONB GIN）。准确表述为：**27 张表中仅 2 张有二级索引，业务基表外键全裸**。
- **影响** 10 万 visit / 30 万 orders / 30 万 charge 时，`getDetail()` 单次 2 次 Seq Scan 各 30 万行，`listPage` 每页扫描约 600 万行，P95 从 <10ms 退化到秒级。
- **建议** 按 PERF-01 给出的 25 条 DDL 建索引，优先 `orders(visit_id)`、`charge(visit_id, pay_status)`、`visit(patient_id)`、`visit(dept_id)`、`exam_task(station, status, seq)`、`queue_board(station, status)`、`appointment(patient_id/slot_id)`、`registration(dept_id, created_at)`；生产用 `CREATE INDEX CONCURRENTLY`。

### R-05 写事务内 `selectList(null)` 全表扫描，WHERE 未下推
- **维度** 性能（PERF-02 + TEST 补充）｜**共识** 三方一致，TEST 补充了关键机理
- **位置** `clinical/application/VisitService.java:200,235,262,289,339,359`；`PatientService.java:99`
- **证据**
```java
List<Charge> unpaid = chargeMapper.selectList(null).stream()      // 无 WHERE
        .filter(c -> visitId.equals(c.getVisitId()) && "UNPAID".equals(c.getPayStatus()))
        .toList();
```
- **TEST 的补充裁决** 这不是"缺索引"，而是**根本没下推 WHERE**，加索引也救不了；且发生在 `@Transactional` 内，持锁时间随数据量线性增长。
- **影响** 单次结算/退费/结束就诊 = 全表物化 charge（30 万行 ≈ 60MB 堆分配）并在事务内持锁；配合 R-23（连接池 10）构成低成本 DoS（见 R-29）。
- **建议** 全部改为 `LambdaQueryWrapper.eq(...)`（`ChargeService.java:30-31` 已是正确写法）；`finishVisit` 的 `hasUnpaid` 用 `selectCount`；`pay()` 用单条 `UPDATE ... WHERE visit_id=? AND pay_status='UNPAID'`。

### R-06 金额计算零边界 / 零精度测试
- **维度** 测试（TEST-01）｜**共识** PERF 确认补测无性能副作用，维持 Critical
- **位置** `VisitService.java:78,134,191,389-391`；`VisitReadModelService.java:55,92`
- **证据** 全仓唯一金额断言是 `VisitReadModelTest.java:52` 的 `31.00`（15.50×2）；`VisitServiceTest`/`VisitServiceLifecycleTest` 零 BigDecimal 断言（`:76` 造了 `100.00` 的 Charge 却从不断言）。
- **影响** `unitPrice == null` → NPE → 建单整单失败；`quantity` 为负 → 负金额 Charge → 可构造"负额结算"；全链路无 `setScale(2, HALF_UP)`，scale 漂移零守护。这是唯一一处**出错后既不抛异常、也不被任何断言发现、且直接对应真金白银**的数据。
- **建议** 补 `VisitServiceOrderAmountTest`：`nullUnitPrice_throws`、`negativeQuantity_throws`、`zeroQuantity_zeroAmount`、`scaleRounding_usesExplicitScale`；生产侧先补 `@Valid` 的 `@Positive`/`@NotNull` 与显式 `setScale`。

---

## 三、High 问题（22 条）

> **已修复（34 项）**：R-07、R-08、R-09、R-10、R-12、R-13、R-14、R-15、R-16、R-17、R-18、R-19、R-20、R-22、R-23、R-24、R-25、R-26、R-27、R-28、R-37、R-38、R-41、R-42、R-43、R-45、R-46、R-48、R-49、R-50、R-51、R-52、R-53、R-54
> **部分修复**：R-11（notification 仍 permitAll）、R-21（收费记录已批量，医嘱因需回填自增主键保持逐条，留 TODO P3）、R-47（缺号源生成/清理/状态机用例，仅补了并发与幂等）
> **未启动**：无

| 编号 | 标题 | 维度 | 位置 | 备注 |
|---|---|---|---|---|
| R-07 | `/api/patient/**` 已认证但无授权、无归属校验，可批量读 PII 并篡改他人档案 | 安全(SEC-04) | `patient/api/PatientController.java:107-138` | **措辞修正**：非"匿名可访问"（`anyRequest().authenticated()` 已拦匿名）；**撤回**"改手机号劫持账号"子项（`phone` 有 UNIQUE 约束且 `password` 不变） |
| R-08 | 就诊 / 病历 / 报告读接口 IDOR，且 `list()` 在 `currentDeptId==null` 时返回全量 | 安全(SEC-05) | `MedicalRecordController.java:33,43,70`；`VisitController.java:75,90,166`；`ReportController.java:33,74`；`VisitService.java:499-502` | SEC 质询后**证据升级**：患者账号 `currentDeptId()` 返回 null → 一次请求拉全量就诊 |
| R-09 | 报告创建 / 发布无鉴权，`doctorId` 前端可控 → 可冒名医生发布报告 | 安全(SEC-06) | `report/api/ReportController.java:58-77` | 破坏医疗数据完整性 |
| R-10 | 全院统一弱口令 `123456` + 改密仅校验 4 位 + 无失败锁定 | 安全(SEC-07) | `DataInitializer.java:33`；`PasswordController.java:40`；`AuthService.java:41-52` | **与 R-45 有前置依赖**：先做登录限流再提 BCrypt cost |
| R-11 | gateway / notification / file 三服务 `permitAll`，无 iam profile 实现 | 安全(SEC-08) | 三个模块各自的 `SecurityConfig` | 仓库中不存在 `application-iam.yml` |
| R-12 | **登录把明文密码放进 URL query** | 安全(SEC-20，质询新增) | `hospital-web/src/api/auth.ts:16`；`platform/api/AuthController.java:26-29` | 主席已复核确认。密码进入浏览器历史 / 网关 access log / Referer |
| R-13 | SSE：`SseEmitter(0L)` 无超时、无心跳、无连接上限、无鉴权，且在 `@Transactional` 内同步广播 | 安全(SEC-16)+性能(PERF-10) | `DispatchSseController.java:32,37`；`NotificationController.java:29,60`；`DispatchService.java:102,123,227,249,267,291` | **合并计数**，一处代码、一次修复；notification 侧完全匿名 → 资源耗尽型 DoS |
| R-14 | **SSE 的 `station` 过滤是空实现**，全量广播 | 性能(PERF-19，质询新增) | `DispatchSseController.java:32,36,42,52-72` | 主席已复核：声明了参数、文档承诺过滤、实现把 station 丢弃。TEST 主张升为契约缺陷 |
| R-15 | 启动全量重建读模型 + 全量账号迁移 | 性能(PERF-03) | `DataInitializer.java:44-48,71-101`；`VisitReadModelService.java:117-128` | **Critical→High**：SEC/PERF 共识"启动窗口放大"；但 SEC 的"无删表"论据被驳回（`:60` 确有 DELETE），PERF 的"try/catch 可隔离"论据被驳回（同类自调用无独立事务，PG 下 `25P02` 会致整个 initAll 回滚）。>5 万 visit 或单条 >20ms 时回升 Critical |
| R-16 | 读模型写放大：单次写 ~8 次查询 + 末尾 `getDetail()` 再 6 次 | 性能(PERF-04) | `VisitReadModelService.java:40-103`；`VisitService.java:92,121,174,214,240,274,302,372` | 建议改 `AFTER_COMMIT` + `@Async` 异步刷新 |
| R-17 | `listPage` 走读模型后仍逐行回查，退化成 1+3N | 性能(PERF-05) | `VisitService.java:446-480` | pageSize=10 → 32 次 SQL；与 R-04 有前置依赖 |
| R-18 | `VisitService.list()` 三表全量加载 + 循环内再全表，O(V×O) | 性能(PERF-06) | `VisitService.java:498-525` | 与 R-07 同源修复：下推 `patient_id`/分页后同时消失 |
| R-19 | lab / pharmacy / report 名称解析逐行回查 N+1 | 性能(PERF-07) | `LabService.java:258-312`；`PrescriptionService.java:286-302`；`ReportService.java:143-189` | 建议统一 `namesByIds` 批量 + Caffeine 缓存 |
| R-20 | 关键字搜索 `%kw%` 前置通配 + 三列 OR，索引完全失效 | 性能(PERF-08) | `VisitService.java:426-431`；`PatientService.java:103-108` | 建议 `pg_trgm` GIN 或中文 `zhparser` + `tsvector` |
| R-21 | 收费 / 医嘱逐条 `updateById` / `insert`，无批量 | 性能(PERF-09) | `VisitService.java:75-90,292-296,349-367` | 单条 UPDATE 替代 + `reWriteBatchedInserts=true` |
| R-22 | `RestTemplate` 无连接 / 读超时 | 性能(PERF-11)+安全(SEC-17) | `platform/config/AsyncConfig.java:31-34`；`report/infrastructure/FileServiceClient.java:59,71` | file-service 挂起 → 4 个 PDF 线程永久阻塞 + Tomcat 线程泄漏 |
| R-23 | HikariCP 未配置（默认 10 连接） | 性能(PERF-13) | `application.yml:11-15` | **Medium→High**：SEC-18 推动，单次请求持连接可达数秒，10 并发即耗尽 |
| R-24 | **`logging.level.com.hospital: debug` 全站开启** | 性能(PERF-16，质询新增) | `application.yml:43` | 热路径 `log.debug` 字符串拼接无 `isDebugEnabled` 守卫 + Console Appender 的 `System.out` 是 synchronized；写接口 CPU/RT +20~40%；顺带 PHI 落日志（见 R-32） |
| R-25 | 重复预约无幂等校验（同患者同号源可下多单） | 测试(TEST-02b) | `BookingService.java:177-216` | 纯 Java 缺陷、单测可覆盖、零覆盖。防超卖本身由 DB 原子 UPDATE 保证（见 R-46） |
| R-26 | 无 JaCoCo、无覆盖率门槛、无 Testcontainers | 测试(TEST-03) | 根 `pom.xml:74-91`；`hospital-core/pom.xml:127-144`；`ci.yml:63-64` | 全仓 grep `jacoco` 零命中 → "缺口有多大"不可度量 |
| R-27 | notification / file / gateway 三模块 23 个生产文件零测试 | 测试(TEST-04) | 三模块 `src/test` 不存在 | 最该先做：三个模块各有独立 `SecurityConfig`，配错即全量放行 → 3 条"未认证必须 401"冒烟 |
| R-28 | Controller 19/23 零 MockMvc，FHIR 也无 JSON 契约测试 | 测试(TEST-05) | `FhirApiTest.java:22-36` 仅断言 3 个字段 | FHIR 是对外互操作契约，字段改名测试全绿但对端解析失败 |

---

## 四、Medium 问题（26 条）

> **已修复（11 项）**：R-30（上传正则 + 20MB + 禁止覆盖）、R-31（审计失败也留痕）、R-32（错误信息不再直出）、R-35（CORS 不再通配）、R-36（中间件端口收回环 + `.env.example`）、R-39（element-plus 按需引入 + 解析回归测试）、R-40（轮询加页面可见性感知）、R-44（审计写入移出业务线程，失败降级为同步）、R-55（CI 前端门禁）
> **已缓解**：R-29（全表扫描与长事务已由 R-04/R-05 治理，锁等待放大面显著收窄；未单独做压测验证）
> **部分修复**：R-33（`/actuator` 已收口，Swagger 仍放行）、R-34（改密吊销已实现，`?token=` query 回退未动）
> **未启动**：无

| 编号 | 标题 | 维度 | 位置 |
|---|---|---|---|
| R-29 | 全表扫描 + 长事务 → 锁等待型 DoS 放大器 | 安全(SEC-18) | `VisitService.java:200-359`；`application.yml:11-15` |
| R-30 | 上传无类型/大小校验 + `objectName` 客户端可控 → 报告对象可被覆盖投毒 | 安全(SEC-11+24) | `file/api/FileController.java:55-64,94-114` |
| R-31 | 审计：`jp.proceed()` 抛异常时审计丢失 + 读接口零审计 | 安全(SEC-23) | `platform/aspect/AuditLogAspect.java:37-52` |
| R-32 | `server.error.include-message: always` + DEBUG 日志 → PHI 落盘 | 安全(SEC-22) | `application.yml:3-4,43`；`AuthService.java:70`；`PatientController.java:64` |
| R-33 | Swagger / OpenAPI 匿名暴露（gateway/notification 的 actuator 亦放行） | 安全(SEC-21) | `SecurityConfig.java:70-71`；网关 `application.yml:61-64` |
| R-34 | JWT 无吊销机制 + SSE 用 query 传 token | 安全(SEC-09) | `JwtAuthFilter.java:56-62` |
| R-35 | 通知服务 CORS `allowedOriginPatterns("*")` + `allowCredentials(true)` | 安全(SEC-10) | `notification/config/CorsConfig.java:19-23` |
| R-36 | docker-compose / application.yml 内置弱口令，中间件端口全暴露 | 安全(SEC-12) | `docker-compose.yml:43,72-73,90` |
| R-37 | `queue_board` 全量加载 + Java 排序，无清理机制（年增 55 万行） | 性能(PERF-12) | `DispatchService.java:185-195,295-300` |
| R-38 | `callNext` 循环内两次查询；`reorderToTail` 全站加载求 `max(seq)` | 性能(PERF-14) | `DispatchService.java:206-244`；`RegistrationService.java:40-43` |
| R-39 | 前端大屏全量拉患者 + element-plus 全量引入 | 性能(PERF-15) | `OutpatientScreenView.vue:48`；`main.ts:4-5,17` |
| R-40 | 轮询与 SSE 并存，重复拉取且无可见性感知 | 性能(PERF-20) | `OutpatientScreenView.vue:34`；`PatientMyQueueView.vue:48` |
| R-41 | PDF 生成：字体每次从磁盘重解析 + 下载路径同步阻塞 Tomcat 线程 | 性能(PERF-21) | `ReportPdfGenerator.java:88-94`；`PatientController.java:67,83` |
| R-42 | `book()` 先 insert 再 update 同一行；`ensureSlotsExist` 无批量 | 性能(PERF-22) | `BookingService.java:112-133,189-203` |
| R-43 | `listPage` 的 `inSql` 字符串拼接 + `orders.execution_dept_id` 无索引 | 性能(PERF-23) | `VisitService.java:421-422,441-442` |
| R-44 | 审计同步写库（41 处 `@AuditLog` 每个写请求 +1 次 INSERT） | 性能(PERF-17) | `AuditLogAspect.java:37-52` |
| R-45 | BCrypt cost 10→12 需先落地登录限流，否则引入 CPU 型 DoS | 性能(PERF-18) | `SecurityConfig.java:48`；`AuthService.java:41-52` |
| R-46 | 防超卖缺 DB 级并发集成用例（mock 伪并发价值极低） | 测试(TEST-02a) | `BookingServiceTest.java:166-187`；`SlotMapper.java:17` |
| R-47 | BookingService 仅 `book()` 有测试，号源生成/清理/状态机裸奔 | 测试(TEST-06) | `BookingService.java:84-244` |
| R-48 | ArchUnit 未守护"禁止绕过 `transitTo` 直接 `setStatus`" | 测试(TEST-07b) | `VisitStatus.java:20-48`；`ArchitectureTest.java:27-67` |
| R-49 | 集成测试断言依赖全局种子数据（`id=1`/`total==1`/固定 keyword） | 测试(TEST-08) | `FhirApiTest.java:32`；`VisitReadModelTest.java:67,71` |
| R-50 | `DataInitializer` 在测试中无条件执行且零断言，基线被隐性改写 | 测试(TEST-10b) | `DataInitializer.java:44-101`；`ci.yml:13-14` |
| R-51 | `JsonbTypeHandler` 零测试，非法 JSONB 静默返回 String → 远端 CCE | 测试(TEST-09) | `JsonbTypeHandler.java:68-77` |
| R-52 | `listPage` 分页边界零覆盖（`pageNum=0` → 负 OFFSET） | 测试(TEST-11) | `VisitService.java:441` |
| R-53 | `finishVisit(force)` 的退款分支与事件副作用未断言 | 测试(TEST-12) | `VisitService.java:348-368`；`VisitServiceTest.java:126-136` |
| R-54 | `MenuServiceTest` 污染 `SecurityContextHolder`（无 `clearContext`） | 测试(TEST-13) | `MenuServiceTest.java:57-59,93-95` |
| R-55 | CI 无前端门禁：无 npm step、无 type-check、无 lint | 测试(TEST-15b) | `.github/workflows/ci.yml:53-64` |

## 五、Low 问题（7 条）

> **已修复（6 项）**：R-56（token 由 localStorage 改 sessionStorage，并收口 storage 访问）、R-57（空密码免密分支已删除）、R-58（启动期破坏性 DELETE 改为探测+告警，不再静默删数据）、R-59（`VisitStatus` 参数化矩阵，固化现状）、R-60（PDF 字体路径跨平台 + 产物断言）、R-61（引入 vitest，19 个前端用例）
> **未启动**：无

| 编号 | 标题 | 维度 | 位置 |
|---|---|---|---|
| R-56 | 前端 token + authorities 存 localStorage，令牌 8h 不可吊销 | 安全(SEC-13) | `stores/auth.ts:55-68` |
| R-57 | 空密码"免密登录"后门代码路径（主流程不产生 NULL 行） | 安全(SEC-14) | `AuthService.java:48-52`；`PasswordController.java:54` |
| R-58 | 启动期 `DELETE FROM org.staff` 等破坏性 DML 放在 `CommandLineRunner` | 安全(SEC-19)/测试(TEST-10) | `DataInitializer.java:53-63` |
| R-59 | `VisitStatus` 缺参数化转换矩阵测试；`of(null)` 静默返回 CREATED | 测试(TEST-07a) | `VisitStatus.java:43-47` |
| R-60 | PDF 字体路径默认 Windows-only，CI(ubuntu) 行为不同；PDF 产物无断言 | 测试(TEST-14) | `ReportPdfGenerator.java:43,90-94` |
| R-61 | 前端零测试（无 vitest / playwright 依赖与脚本） | 测试(TEST-15) | `hospital-web/package.json:6-11,20-25` |

---

## 六、交叉质询裁决记录（分歧与结论）

| # | 争议点 | 各方立场 | 主席裁决 |
|---|---|---|---|
| 1 | PERF-03 定级 | SEC：Critical→High（有 try/catch、无删表）｜PERF：接受降级，驳回论据 | **降为 High**。SEC 结论对但论据错（`:60` 确有 DELETE）；PERF 反驳对但同类自调用无独立事务，try/catch 不隔离。收录双方论据 |
| 2 | TEST-10 是否"每次启动删数据" | SEC：夸大，有幂等闸门，且应迁出测试维度｜TEST：接受降级，但反驳"迁出" | **降为 Low 并记 SEC-19**；同时**新立 R-50(Medium)**：启动期副作用在测试中零断言。两者修法不同，不合并 |
| 3 | 审计丢失的机理 | SEC：外层事务回滚连带抹掉｜PERF：Controller 无事务、不持业务锁｜TEST：是**异常短路**，异常在 insert 之前抛出 | **采纳 TEST**。已复核 `AuditLogAspect.java:39,49`：无 try/finally，异常直接中断 → 第 49 行永不执行。故"加 `REQUIRES_NEW`"无效，必须改 `try/finally` |
| 4 | PERF-15 归因 | SEC：根因在后端 `PatientController.list()`，记在前端名下会误导修复 | **采纳 SEC**。R-39 前端部分降为 Medium，安全含义并入 R-07，两者标为同源修复 |
| 5 | TEST-08 "依赖真实 PG" | PERF：这是性能验证前提，改 H2 是净损失｜TEST：接受撤回前半句 | **High→Medium**。`schema.sql` 用了 `BIGSERIAL`/`USING GIN`/`regclass`，H2 根本跑不起来；保留真实 PG + Testcontainers，只消除数据耦合 |
| 6 | TEST-02 防超卖 | PERF：DB 原子 UPDATE 保证，mock 伪并发价值低，降 Medium｜TEST：接受，但"重复预约"仍成立 | **拆为 R-46(Medium)** 与 **R-25(High)**。降级理由成立：mock 恰好替换掉了承载正确性的那行 SQL |
| 7 | TEST-01 定级 | PERF：补测不新增 SQL，无性能代价 | **维持 Critical**。PERF 的论证指向"成本低"，反而强化"应立即做" |
| 8 | PERF-13 Hikari | SEC-18 认为是 DoS 面｜PERF 升级 | **Medium→High**。单次请求持连接可达数秒 × 10 连接 = 容量天花板 |
| 9 | PERF-01 "零索引" | 三方交叉核查 | **修正措辞**为"27 张表仅 2 张有二级索引，业务基表外键全裸"，定级维持 |
| 10 | SEC-22 "MyBatis SQL 打印" | PERF：全仓无 `log-impl`/`show-sql` 配置 | **撤回该子项**；`com.hospital: debug` 成立并升级为独立性能项 R-24 |
| 11 | SEC-11 响应头注入 | SEC 自查撤回（Spring Security `StrictHttpFirewall` + Tomcat 头部校验） | **撤回**，重心改为 R-30 对象覆盖投毒 |
| 12 | SEC-04 措辞 | SEC 自查 | **修正**为"已认证但无授权/无归属校验"，撤回"改手机号劫持账号" |
| 13 | TEST-07 "状态机无单测" | TEST 自查：实际已覆盖 5 条迁移路径 | **拆为 R-59(Low)** 与 **R-48(Medium)** |
| 14 | 测试类数量 | SEC/PERF 转述为 5 个；TEST 自查 | **实际 18 个测试类 / 101 个 `@Test` / 182 个生产文件（1:10）**；真实问题是"分布偏 + 无度量"而非"数量少" |

**三方一致排除（不再列入问题清单）**：SQL 注入面干净（无 XML mapper，`${}` 零命中，排序用枚举列名）；收费金额不可被前端篡改（`pay(visitId)` 不收金额）；booking 模块的归属校验是全仓唯一规范实现；`NotificationStore` 的 `CopyOnWriteArrayList` + 头插（CAP=200，纳秒级）；`paymentStatus` 已正确批量化；slot 行锁粒度与持锁时间合理；`ChargeService` 单方法覆盖完整。

---

## 七、改进路线图

> **进度（2026-09-09）**：P0 七项 ✅ 全部完成（实际 6 个子代理 + 主席收敛，41 改 / 5 新增）。
> 验证：五个模块 `test-compile` BUILD SUCCESS；`hospital-core` 全量测试通过（106 → 109 个用例，新增 3 个权限回归用例）；前端 `type-check` 剩余 24 个错误均为既有错误。
> 下方 P0 条目保留作为实施记录。

### P0 · 安全止血（建议 48h 内，约 3 人日）— 已完成
1. **R-01** 删 JWT 默认密钥 + 启动 fail-fast + 注入强密钥
2. **R-02** `/fhir/**` 收口鉴权，禁止无参全量返回
3. **R-03** file-service：`patientId` 必填 + 默认拒绝 + 8101/8102/8103 不映射宿主机端口
4. **R-07 + R-08 + R-09 + R-18（同源，一次改造）** 三个 Controller 补 `@PreAuthorize`；患者/科室维度**下推到 SQL**（同时消掉全表扫描）；`doctorId` 改由服务端解析
5. **R-12** 登录改 JSON body + 失败锁定（5 次锁 15 分钟）+ 接口限流
6. **R-13 + R-14（同一改动，勿拆两次工单）** SSE：`SseEmitter(30_000)` + 心跳 + 连接上限 + 鉴权 + 广播移出事务 + 按 station 路由
7. **R-10** 首次登录强制改密 + 密码策略（最小 10 位、复杂度、不与旧密码相同）

### P1 · 性能地基 + 测试门禁（本迭代，约 8 人日）
8. **R-04** 建索引（`CONCURRENTLY`，注意 PG 建索引锁表）— **所有性能修复的前置地基**
9. **R-05** `selectList(null)` 全部下推 WHERE；`pay()` 改单条批量 UPDATE
10. **R-23** Hikari `maximum-pool-size: 30`、`connection-timeout: 5000` — **必须先于 #11/#12 上线**
11. **R-24** `com.hospital` 日志 DEBUG → INFO（顺带关闭 PHI 落日志）
12. **R-17 + R-18** `listPage` 直接拼装读模型（33 次 SQL → 3 次）；`list()` 用 `groupingBy` 提至循环外
13. **R-22** `RestTemplate` connect 3s / read 10s
14. **R-06** 金额校验 + 边界测试
15. **R-26 + R-55** JaCoCo `check`（先设 LINE ≥ 0.40 且不 failBuild）+ CI 增加 node 作业跑 `type-check`/`test`
16. **R-27** 三个零测试模块各补 1 条"未认证访问必须 401"冒烟（成本最低、收益最高）

### P2 · 结构性改造（后续迭代）
17. **R-15** 启动重建改版本号守卫 + `user_id IS NULL` 短路（visit > 5 万行前必须完成）
18. **R-16** 读模型改 `AFTER_COMMIT` + `@Async` 异步刷新
19. **R-31/R-44** 审计改 `try/finally` + `REQUIRES_NEW`，补齐读接口留痕
20. **R-19/R-20/R-21/R-37/R-38/R-41/R-42** 批量化与缓存改造
21. **R-45** 登录限流落地后再提 BCrypt cost 至 12
22. **R-58** DDL/DML 迁到 Flyway/Liquibase

> **严格前置依赖**：#8（索引）→ #12；#10（连接池）→ #11/#12；#13（限流）→ #21（BCrypt cost）。

---

## 八、待人工复核（Agent 无法从代码判定）

1. 生产/预发库 `SELECT count(*) FROM platform.sys_user WHERE password IS NULL;` — **R-57 免密分支已删除，NULL 密码账号现在会直接登录失败**，需提前排查并由管理员走重置接口处理，否则会出现"账号无故登不上"
2. 部署环境是否真的注入了 `app.jwt.secret` / `PG_PASSWORD` / MINIO 密钥；CI/CD 环境变量清单核查
3. 8101/8102/8103 在目标环境的安全组 / 网络策略是否真的隔离（决定 R-03/R-11 的实际可达性）
4. MinIO 对含 `../` 对象 key 的实际归一化行为（决定 R-30 是否需再升级）
5. 依赖 CVE：Spring Boot 3.2.5 / jjwt 0.12.5 / minio 8.5.7 / flying-saucer 9.1.22 按版本号无已知高危，建议跑一次 `osv-scanner` 或 OWASP Dependency-Check 确认
6. `listPage` 补齐索引前后的真实 P50/P95 对比（需 10 万级数据 + `EXPLAIN ANALYZE`）
7. SSE 广播在事务内同步执行的持锁放大（需模拟 100-300 订阅者 + 慢客户端实测）
8. `pdf_status=FAILED` 在 CI(ubuntu) 上的实际占比（Windows 字体路径导致恒走降级分支）
