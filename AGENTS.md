# AGENTS.md — hospital-system 开发约定

> 本文件为 Claude Code / Codex 等编码代理在本仓库协作时的**唯一入口文档**(`CLAUDE.md` 通过 `@AGENTS.md` 指向本文件)。
> 只保留「写错代价高、不翻代码发现不了」的铁律;业务闭环、演示账号表、技术选型见根目录 `README.md`,
> 架构推导与决策记录见 `docs/architecture-design.md`、`docs/adr/`;代码审查结论与实施状态见 `docs/review/`。
> 需要改某个业务流程时,先读对应小节与源码再动手。

## 项目一句话

个人学习 / 求职作品向医院 HIS:**模块化单体核心**(`hospital-core`)+ 通知 / 文件 2 个抽离服务,
统一置于 Spring Cloud Gateway 之后;前端为 Vue3 单 SPA,同时承载 B 端(内部工作台)与 C 端(患者门户,`patient/*` 路由)。

## 常用命令

```bash
docker compose up -d                 # 中间件:PG16 / Redis / MinIO / RabbitMQ
mvn clean install -DskipTests        # 构建全部后端模块
mvn -B clean verify                  # 构建 + 测试(CI 流水线,含 ArchUnit)

mvn -pl hospital-core spring-boot:run                        # :8101
mvn -pl hospital-notification-service spring-boot:run        # :8102
mvn -pl hospital-file-service spring-boot:run                # :8103
mvn -pl hospital-gateway spring-boot:run                     # :8104

cd hospital-web && npm install
npm run dev                          # :5173,Vite 代理 /api → 网关
npm run type-check                   # vue-tsc
npm test                             # vitest(jsdom + @vue/test-utils)
```

## 关键配置(不设也能本地跑,生产必看)

| 配置 | 不设的后果 |
|---|---|
| `APP_JWT_SECRET` | 生产**拒绝启动**;非 prod 会临时生成随机密钥 —— 重启即全员掉线,且 notification-service 校验不了 core 签发的 SSE ticket(该场景下它选择"放行 + WARN") |
| `FILE_INTERNAL_TOKEN` | core ↔ file-service 的内部令牌,两侧必须同一把;file-service 在生产未配置则拒绝启动 |
| `PDF_FONT_PATH`(即 `app.report.pdf.font-path`) | 留空时按候选路径探测;**容器 / CI 无中文字体时 PDF 中文渲染成方框**(降级不报错,静默劣化) |
| `app.reconcile.cron` | 下游单据对账频率,默认每 10 分钟。调大可延迟自愈,但不要去掉该任务 |

## 后端铁律(ArchUnit 强制,违例即构建失败)

- 每个业务域模块 4 层:`api`(REST)→ `application`(服务)→ `domain`(实体/值对象)← `infrastructure`(Mapper)。
  `domain` **只允许被 `api` / `application` / `infrastructure` 访问** —— 通用组件若要碰实体 / 值对象,就放这三层
  (例:审计写入器放 `platform.infrastructure` 而不是 `platform.support`,放错会直接构建失败)。
- 模块隔离:`clinical` 不得依赖 `pharmacy` / `lab` / `operation` / `integration`;`platform` 为共享内核,所有模块可依赖。
- 新业务域复制上述 4 层骨架;通用横切(审计、消息、统一账号、调度)放 `platform`,**业务逻辑不得写进 platform**。
- 关键写操作用 `@AuditLog` + `AuditLogAspect` 自动落 `audit_log`(Controller 接口想入审计就加注解,勿手写)。
  **不经 Controller 的关键动作**(事件监听器 / `@Scheduled` 任务)切面拦不到,必须显式调 `platform.infrastructure.AuditRecorder`;
  不要直接 `auditLogMapper.insert` —— 该组件封装了「写审计失败绝不影响业务」与「异步 + 执行器不可用时降级同步」两条语义,
  另写一套会让两条路径的失败行为不一致。
- **跨模块的「状态变更 → 生成下游单据」必须走 Spring 事件**,不要直接注入其它模块的 service(直接调会踩模块隔离红线)。
  范式:事件放在**发布方的 `domain`**(自包含快照,只带 id 等标量,消费方无需回查发布方),消费方在**自己的 `application`** 里用
  `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 监听。
  现成范例:`booking → dispatch`(预约生成检查任务)、`clinical → lab/pharmacy`(确单生成检验申请 / 处方)。
  监听器**必须吞掉异常**:发布方事务已提交,抛出只会让接口返回 500 而主流程其实已成功;失败留给对账任务兜底。

## 数据库(单 PG16,唯一权威脚本)

- **唯一建表 / 种子脚本**:`hospital-core/src/main/resources/db/schema.sql`。
  docker-compose 将其挂为 postgres 容器 initdb(仅全新 `pg-data` 卷时执行)。改表就改它,勿另建 SQL 文件。
- 实体必须 `@TableName("schema.table")`(MyBatis-Plus,`map-underscore-to-camel-case: true`)。
- 全部表在 `hospital` 库内按 schema 隔离:

| Schema | 关键表 |
|---|---|
| `platform` | `sys_user`、`sys_user_role`、`role`、`role_authority`、`menu`、`menu_authority`、`audit_log` |
| `clinical` | `visit`、`visit_read_model`(CQRS)、`orders`、`charge`、`registration`、`medical_record`(JSONB) |
| `patient` | `patient`(含 `user_id` 关联统一账号) |
| `booking` | `exam_package`、`exam_item`、`slot`、`appointment` |
| `dispatch` | `exam_task`、`queue_board` |
| `pharmacy` | `prescription`、`prescription_item` |
| `lab` | `requisition`、`result_item` |
| `report` | `record` |
| `org` | `department`、`staff`(含 `user_id` 关联统一账号) |

- 幂等种子用 `INSERT … SELECT … WHERE NOT EXISTS`;需要每次启动重算的逻辑放 `DataInitializer`。

## 登录与账号(现状,勿再引入 dev 自动登录)

- **统一真登录**:手机号 + 密码,自管 JWT(HS256),**无 dev 模拟登录开关**;未登录由路由守卫跳 `/login`。
- 登录接口:`POST /api/auth/login`,**JSON 请求体** `{phone, password}` → token + roles + authorities(七权并集)。
  刻意**不走 URL query**(密码进 URL 会被 access log / 浏览器历史 / Referer / 网关注日志记录);别为"方便"改回去。
- **演示账号不直接种在 `sys_user`**:`DataInitializer` 启动时把 `org.staff`(角色 = `staff.position`)与
  `patient.patient`(→ PATIENT)按 phone 幂等迁移进 `platform.sys_user`,默认密码统一 `123456`(BCrypt)。
  要增 / 改演示账号,改上述两张表的种子,不要手插 sys_user。演示账号表见 `README.md`。
- 七权 RBAC:`visit:entry` / `visit:audit` / `order:execute` / `pharmacy:dispense` / `charge:pay` / `system:admin` / `patient:booking`。
- 后端鉴权:`@PreAuthorize("hasAuthority('...')")`;前端菜单 = 后端 `MenuService` 按当前用户 authorities
  从 `platform.menu` + `platform.menu_authority` **动态裁剪(不是代码硬编码)**。
- **SSE 鉴权**:`EventSource` 无法带自定义头,故先 `POST /api/core/sse/ticket`(Bearer)换 **60 秒、`scope=sse`** 的 ticket,
  再以 `?ticket=` 订阅 `/api/core/**/sse/**`;`JwtAuthFilter` 显式**拒绝**把 ticket 当普通令牌用(否则 URL 上的凭证就能换全量 API 访问)。
  前端 SSE 客户端需**自行退避重连** —— 原生自动重连会复用已过期 ticket,必然失败。
- **SSE 超时后的 ASYNC 派发必须放行**(R-65,core 与 notification 的 `SecurityConfig` 均如此):SSE 连接 60s 到点后
  容器会以 `DispatcherType.ASYNC` 把请求重进过滤器链,那次派发**没有身份上下文**(自定义过滤器是
  `OncePerRequestFilter`,默认跳过 ASYNC),AuthorizationFilter 会把它当匿名 → 响应已提交 → 日志刷
  `Unable to handle the Spring Security Exception because the response is already committed`。
  故每条 `authorizeHttpRequests` 首条规则是 `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` —— 这是修法不是放水,
  别改回 `authenticated()`;守护测试 `SecurityConfigAsyncDispatchTest` / notification `SecurityConfigTest` 会拦下。

## 前端

- Vue 3 + TypeScript + Vite + Element Plus(**具体版本以 `hospital-web/package.json` 为准,勿在文档固化**);
  路由 / 视图清单同理,以 `src/router/index.ts` 与 `src/views/` 为准。
- Element Plus 组件走**按需引入**(`vite.config.ts` 的 `unplugin-vue-components` + `ElementPlusResolver({ importStyle: false })`),
  但**样式仍全量引** `element-plus/dist/index.css`:主题映射层 `styles/element.css` 依赖它在该 CSS 之后加载,
  且 `ElMessage` 这类**显式 import 的函数式 API 不走 resolver**。
- **`main.ts` 里的 `app.use(ElLoading)` 不能删** —— 按需解析插件只处理 `<el-xxx>` 标签、**不处理 `v-loading` 这类指令**,
  删掉后全仓的 `v-loading` 会静默失效(构建与类型检查照样通过,只有真跑页面才看得出)。
- B 端与 C 端同仓库:`patient/*` 顶层路由为患者门户,其余为工作台;两端可见性由后端菜单裁剪控制,前端不做权限硬编码。
- 状态按业务域拆 Pinia store;`api/http.ts` 统一 Bearer 拦截;登录态持久化在 **sessionStorage** —— 刷新免登,但**新开标签页不共享登录态**
  (这是刻意收窄 XSS 窗口的取舍,别再改回 localStorage)。storage 访问前必须探测可用性(禁用 Storage 时直接读会在模块加载阶段抛错 → 整站白屏)。

## 业务速查(细节点开对应源码 / README「业务闭环」)

- **就诊状态机**:叫号生成就诊单 `CREATED` → 医生点「确单」锁定医嘱并生成处方 / 检验申请 `CONFIRMED` →
  收费 `IN_PROGRESS` → 医嘱全部执行 / 取消后 `FINISHED`。`CREATED` 不可结算;已确单后仍可追加医嘱,新医嘱自动并入既有单据。
- **下游单据不变式**:「就诊已确单 ⇒ 每条 LAB / MEDICATION 医嘱都被对应单据明细覆盖」。生成由**服务端**完成 —— 确单时批量发一次事件、
  已确单后追加医嘱时单条发一次,**前端不再发第二个请求**(历史上正是那一步没有补偿,导致单据静默缺失、连审计都不留痕)。
  `DownstreamDocReconcileJob` 每 10 分钟把漏生成的补上(补建记录的 actor 为 `system`,与人工操作可区分)。
  这是本仓**唯一刻意采用最终一致**的链路(确单是临床主流程,不能被下游拖垮),与「就诊 / 医嘱 / 收费同事务强一致」的取舍互不冲突。
- **文件访问**:统一经 core 的 `/api/core/files` 代理(upload / list / download),归属判定放在 core —— 只有那里有身份上下文。
  file-service(`:8103`)只在内网可达;**网关不得加回 `/api/files` 路由**(网关层没有身份上下文,直连等于匿名可枚举 / 下载全部患者报告;
  `GatewayRouteGuardTest` 有反向断言守着)。
- **挂号叫号**:取 WAITING 最小排队号 → 自动建就诊单并置 CALLED(关联 visitId);科室分诊屏 + SSE 推送。
- **体检预约**:`book()` 在 DB 行锁下原子占号(`booked < capacity`),成功后落 `exam_task` + `queue_board`;号源由 `SlotGenerateJob` 每日生成。
- **P2 特性(已落地)**:CQRS-lite 读模型 `visit_read_model`;FHIR Facade(只读、手写 JSON);结构化病历 `medical_record`(JSONB + `JsonbTypeHandler`)。

## 工程质量

- 测试:JUnit 5 + Mockito + AssertJ;纯单测不启 Spring 上下文,需要连真库 / 验证事务与事件的用 `@SpringBootTest`
  (自造数据 + 自清理,勿依赖 `schema.sql` / `DataInitializer` 播下的种子数据)。架构红线在 `ArchitectureTest`。
  - **别给「事务提交后才触发」的逻辑套 `@Transactional`**:`@TransactionalEventListener(AFTER_COMMIT)` 在永不提交的测试事务里
    根本不触发,测试会给出"绿"的假象 —— 这类用例必须**不带** `@Transactional`,并自行清理数据。
  - 前端 vitest + jsdom + `@vue/test-utils`(`npm test`);`vitest.config.ts` **复用 `vite.config.ts` 的插件**,
    否则「按需引入漏掉某个组件 / 指令」这类问题在测试里永远暴露不出来(构建成功 ≠ 组件可用)。
- 输入校验:Request DTO 用 jakarta validation,Controller 参数加 `@Valid` → `GlobalExceptionHandler` 统一 400。
- 错误响应统一 `{timestamp, status, error, message}`;前端 ErrorBoundary + Axios 拦截器统一提示。
- Swagger:`:8101/swagger-ui.html`(直连)/ `:8104/swagger-ui.html`(经网关)。
- CI:GitHub Actions,JDK 21(Temurin)上执行 `mvn -B clean verify`(push main/master 与 PR)。
