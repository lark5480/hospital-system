# 医院信息系统 · 整体架构设计与长期发展规划

> **范围约束**:个人学习项目(模拟单体医院业务域) · 全新绿地 · 本地运行(不上公网/不买服务器域名) · 研发者本人(可 AI 辅助) · DB 由 Oracle 转学 PostgreSQL
> **学习深度目标(了解即可,非强制)**:等保三级 / 电子病历评级 / 互联互通(HL7·FHIR) / 个人信息保护——作为简历深度与知识补强方向,按需渐进,不构成本地项目的硬约束。

> **项目定位**:个人学习 + 求职作品集。本地运行、代码托管于个人 GitHub 仓库(便于管理 / 备份 / 展示),不对外公网部署。目标:补回实习落下的医院域知识、拓展简历广度与深度,并从 Oracle 迁移学习 PostgreSQL。

> **⚠️ 历史说明**:本文档中的 ADR-006、ADR-012 等涉及 Keycloak 的决策已过时。系统已从 Keycloak 迁移至**自管 JWT**(JwtTokenService + JwtAuthFilter),Keycloak 相关代码和配置已删除。当前认证方案见 ADR-012 的最新实现(自管 JWT + 七权分立 RBAC)。

---

## 一、结论先行

**推荐架构:模块化单体(Modular Monolith),而非微服务。**

一句话理由:在「单家医院 + 团队 < 10 + 数据不出院」的约束下,微服务的分布式成本(运维、事务、网络)远高于其带来的独立伸缩收益;而模块化单体既能从第一天就拿到「边界清晰、可维护、可演进」的好处,又保留了未来按需抽出服务(strangler fig)的退路。

三个核心抓手:
1. **边界内建**:模块按业务域划分,依赖方向由 ArchUnit 在 CI 强制,防止退化成「大泥球」。
2. **互操作前置**:集成模块从第一天就讲 HL7 v2 / FHIR R4,为互联互通评级和医保对接铺路。
3. **合规内建**:认证、审计、EMPI、加密、五权分隔作为平台基础,而非事后补丁。

---

## 二、约束与假设

| 维度 | 取值 | 对架构的含义 |
|---|---|---|
| 系统范围 | 模拟单体医院 | 练习用,单部署单元足够 |
| 建设起点 | 全新绿地 | 可自由选型,从零定义边界 |
| 部署形态 | 本地运行 | 不上公网/不买服务器;Docker Compose 起全套 |
| 团队规模 | 本人(AI 辅助) | 学习项目,精力放业务域与深度 |
| 合规(学习) | 等保/互通了解 | 作为深度目标,非本地硬约束 |

---

## 三、架构模式决策(含取舍)

| 候选 | 采用? | 理由 |
|---|---|---|
| 微服务 | ✗ | 分布式事务、N 套 CI/CD、网络分区故障,小团队扛不住 |
| 模块化单体 | ✓ | 边界清晰 + 单部署运维;未来可抽服务 |
| 纯单体(无模块) | ✗ | 必退化成大泥球,改不动 |
| 事件驱动优先 | △ | 域内用进程内事件总线,跨系统再上 MQ,不冒进 |

**模块化单体 vs 微服务的取舍:**
- **放弃**:单模块独立水平伸缩(单医院峰值可预测,垂直 + 只读副本足够)。
- **放弃**:技术异构自由(整库同栈,但模块内仍可变)。
- **获得**:极低运维负担、强一致性(单库事务)、团队友好。
- **保留退路**:模块边界清晰 → 任一热点模块可 later 抽出为独立服务(strangler fig),无需重写。

---

## 四、容器架构(见配图)

**hospital-core**(模块化单体核心,端口 8101)+ **hospital-gateway**(API 网关,8104)+ **hospital-notification-service**(通知服务,8102)+ **hospital-file-service**(文件服务,8103),共 4 个 Spring Boot 进程。hospital-core 内部 9 个 domain 模块:**临床 / 药事 / 医技(检验) / 报告 / 体检预约 / 排队分发 / 患者 / IAM(菜单) / 平台基础(JWT+RBAC)**。
配套中间件(均已在 `docker-compose.yml` 中配置,`docker compose up` 全量启动):**PostgreSQL 16**、**Redis 7**、**MinIO**(对象存储)、**RabbitMQ 3**(可靠事件)。调度使用 Spring 内置 `@Scheduled`。
> 认证已改为后端自管 JWT,不再依赖 Keycloak;`keycloak/` 目录已删除。
对外:经集成模块对接 **医保网关、区域全民健康平台、第三方 LIS/PACS**(P2 阶段)。

---

## 五、模块边界与依赖规则

- 模块边界由 **ArchUnit** 在 CI 强制(当前已有分层规则 + 一条 `clinical` 单向隔离规则;全代码库未使用 Spring Modulith 的 `@NamedInterface`,Modulith 仅作依赖引入)。应用服务间只能经 published API 调用,禁止跨模块直接访问 DAO。
  - 9 个 domain 模块分属 9 个 schema(`platform`/`clinical`/`patient`/`booking`/`dispatch`/`pharmacy`/`lab`/`report`/`org`),`iam` 模块不持表,只做内存级菜单配置;RBAC(角色↔权限映射)入 `platform.role` + `platform.role_authority` 表,管理员后台可配。
- 用 **ArchUnit** 在 CI 写规则(当前已有分层规则 + clinical 模块隔离规则),违例即构建失败;模块隔离范围目前仅覆盖 clinical→其他 单向,其余模块间依赖(如 booking→patient)守护待补。
- **共享内核最小化**:仅「平台基础(审计/Redis/域事件基础设施)」为共享内核,所有模块可依赖;`iam` 为菜单+RBAC 模块,不持表,其余模块零共享。
- 数据库:每模块通过**独立 schema**(如 `booking`、`clinical`、`pharmacy`)逻辑隔离(同一 PostgreSQL 实例);通过 `db/init.sql`(docker-entrypoint-initdb.d 挂载)在容器首次启动时建库建表;应用层 `spring.sql.init.mode=always` 配合 `schema.sql` 保持幂等做二次保障。尚无需 Flyway/Liquibase。

---

## 六、数据架构

- **系统记录源**:PostgreSQL 16(首选,JSONB 存灵活临床文档、分区表、FDW 做 BI);团队若更熟 MySQL 8 亦可,取舍见 ADR-002。
- **写/读分离(CQRS-lite)**:OLTP 走模块 schema;报表/BI 走只读副本或独立读模型,避免重 join 拖慢业务库。
- **影像/附件**:MinIO 对象存储,DB 只存引用。
- **EMPI**:患者主索引服务,统一跨域患者身份,支撑互联互通。
- **备份**:每日全量 + WAL 归档;PITR 目标 ≤ 15 分钟。

### Schema-per-module 隔离

所有表共享一个 PostgreSQL 16 实例(`hospital` db),按 schema 逻辑隔离:

| Schema | 表 | 所属模块 |
|---|---|---|
| `platform` | `audit_log` | 共享内核 |
| `clinical` | `visit` / `orders` / `charge` | 临床就诊 |
| `patient` | `patient` | 患者注册 |
| `booking` | `exam_package` / `exam_item` / `slot` / `appointment` | 体检预约 |
| `dispatch` | `exam_task` / `queue_board` | 排队分发 |
| `pharmacy` | `prescription` / `prescription_item` | 药事 |
| `lab` | `requisition` / `result_item` | 检验 |
| `report` | `record` | 报告 |

ORM: MyBatis-Plus 3.5.7,`@TableName("schema.table")`,开启 `map-underscore-to-camel-case`。


---

## 七、互操作 / 集成层(关键长期资产)

- **HL7 v2**:对接传统 LIS/PACS/设备(院内主流协议)。
- **FHIR R4**:对接区域全民健康信息平台(互联互通测评)、互联网医院、对外开放 API。
- 集成模块即**防腐层(ACL)**:外部协议翻译为内部领域事件,内部永不被外部模型污染。
- 进程内事件总线(ApplicationEvent)做域内解耦;当需要可靠异步/跨进程,外置 RabbitMQ。

---

## 八、技术选型

| 层 | 选型 | 备注 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3 + Spring Modulith(依赖引入) | Spring Modulith 在 classpath 但当前未实际使用其模块机制(`@NamedInterface`/`@Modulith` 均无);模块边界由 ArchUnit 强制,域内事件走标准 `ApplicationEventPublisher` |
| ORM | MyBatis-Plus | 团队已精通 |
| DB | PostgreSQL 16(或 MySQL 8) | 见 ADR-002;docker-deploy 已用 PG16 |
| 前端 | Vue 3 + TS + Element Plus + Vite | 临床/管理工作站;复用 docker-deploy 的 nginx 反代模式 |
| IAM | 自管 JWT(JwtTokenService + JwtAuthFilter) | 七权分立 RBAC(visit:entry/visit:audit/order:execute/pharmacy:dispense/charge:pay/system:admin/patient:booking),角色/权限入库可调,无外部 IdP 依赖 |
| 缓存 | Redis 7 | 套餐列表查询已接入 Spring Cache(@Cacheable);候选:号源/排队号;docker-deploy 已含 |
| 对象存储 | MinIO | 影像/附件;本地已装 |
| 消息 | RabbitMQ(单体阶段唯一 broker) | 可靠事件外置;勿与 RocketMQ 双跑 |
| 调度 | Spring @Scheduled | SlotGenerateJob(每天 3:00 号源生成) + AppointmentCleanupJob(每天 4:00 过期清理),零外部依赖 |
| 部署 | Docker Compose(复用 docker-deploy 模式) → k3s | 拒绝重 k8s;dev/prod 覆盖 + 健康检查 + 非 root |
| 可观测 | Prometheus + Grafana + Loki + OTel | 日志→Loki;指标→Prometheus;追踪→OTel;**审计日志→PostgreSQL(等保防篡改)**;不引 MongoDB |
| CI/CD | GitHub Actions | 个人仓库;build + test + ArchUnit |

---

## 八-B、本地中间件取舍(对齐 `E:\Software\docker-dev` 与 docker-deploy)

你本地 `docker-dev` 已装一批中间件。按「模块化单体、拒绝微服务期中间件」原则,取舍如下:

| 本地组件 | 决策 | 理由 |
|---|---|---|
| PostgreSQL 16 | ✅ 用 | 与 docker-deploy 一致,系统记录源 |
| MySQL 8 | ⚠️ 备选 | 团队更熟可用,但选其一即可,勿双库 |
| Redis 7 | ✅ 用 | 套餐列表已接入 Spring Cache(@Cacheable);与 docker-deploy 一致 |
| MinIO | ✅ 用 | 影像/附件对象存储 |
| RabbitMQ | ✅ 用(唯一 broker) | 可靠事件;与 RocketMQ 二选一,勿双跑 |
| RocketMQ | ⚠️ 暂缓 | 与 RabbitMQ 同质,单体阶段不必双 broker;若团队偏阿里系可择一 |
| xxl-job | ❌ 已移除 | 仅 2 个简单定时任务,原生 @Scheduled 足够;未来若需分布式调度可接 ShedLock / PowerJob |
| Elasticsearch | ⚠️ P2 再引 | 病历全文检索;P0/P1 先用 PG 全文检索,量大再上 ES |
| MongoDB | ❌ 暂不用 | PostgreSQL JSONB 已覆盖半结构临床文档,避免第二库 |
| Nacos | ❌ 不用(发现) | 单体单进程无需服务发现;配置中心亦非必需 |
| Seata | ❌ 不用 | 单库 ACID 事务即可,分布式事务是微服务期才需 |
| Keycloak | ❌ 已移除 | 曾用于学习 OIDC/RBAC,已迁移至自管 JWT;`keycloak/` 目录已删除 |

> **结论**:`docker-compose.yml` 已包含全部 4 个中间件(`postgres + redis + minio + rabbitmq`),`docker compose up` 一键全量启动,前后端在本地 IDE 运行;前端由 React 换为 Vue 3,经 Vite dev proxy 把 `/api` 转发至 API Gateway(:8104)。

---

## 九、质量属性

- **可用性(目标 99.9%,7×24)**:单部署风险用健康检查 + 蓝绿发布 + DB 主从(Patroni)缓解;非核心模块优雅降级。
- **安全(学习深度)**:审计日志、静态/传输加密、七权分隔、RBAC——理解医疗系统安全要求;本地用自管 JWT + 统一账号体系(手机号+密码+多角色),不堆 WAF/网络分区。
- **可维护性**:模块边界 + ArchUnit 红线,新人不踩跨域坑。
- **可扩展**:垂直 + 只读副本;热点模块按 strangler fig 抽服务。

---

## 十、架构决策记录(ADR)

### ADR-001 模块化单体而非微服务
- **状态**:Accepted
- **上下文**:单医院、团队 < 10、数据不出院,微服务的分布式成本 > 收益。
- **决策**:采用模块化单体,模块按业务域划分,单部署单元。
- **后果**:易 — 运维低、强一致、团队友好;难 — 单模块无法独立伸缩、单部署故障面大(用健康检查/降级缓解)。

### ADR-002 单一关系库 + 每模块独立 schema
- **状态**:Accepted
- **上下文**:需边界隔离又想保留单库事务与简单运维。
- **决策**:PostgreSQL 16 单实例,模块级 schema 隔离;MySQL 8 为团队熟练备选。
- **后果**:易 — 事务简单、备份单一;难 — 跨模块大查询需走集成/读模型,异构存储不可行。

### ADR-003 模块边界由 Spring Modulith + ArchUnit 强制
- **状态**:Accepted
- **上下文**:模块化单体常退化为大泥球。
- **决策**:CI 中 ArchUnit 校验依赖方向,违例即失败。
- **实施现状**:Spring Modulith 在 classpath(`spring-modulith-starter-core`)但**当前未实际使用**——全代码库无 `@NamedInterface` / `@Modulith` / `ApplicationModules` / `ModulithTest`;模块边界与依赖方向实际由 **ArchUnit** 强制,Modulith 仅作依赖引入。域内事件走标准 `ApplicationEventPublisher`。ArchUnit 已有分层规则(`ArchitectureTest`)与一条模块隔离规则 `clinicalMustNotDependOnOtherModules`(clinical 不得依赖 pharmacy/lab/operation/integration);**注意**:该规则以 clinical 为源**单向**守护,且 `operation`/`integration` 包当前不存在,故未覆盖 booking→patient 等其余模块间依赖——模块隔离范围待补。
- **后果**:易 — 长期可维护;难 — 初期需定义清晰 API 边界,有少量前期成本。Spring Modulith 深度使用(`@ApplicationModuleTest`、`@Externalized`)待后续推进。

### ADR-004 互操作层讲 HL7 v2 + FHIR R4(防腐层)
- **状态**:Accepted
- **上下文**:需对接医保、区域平台、第三方医技,且要过互联互通测评。
- **决策**:独立集成模块做协议翻译,内部领域不被外部模型污染。
- **后果**:易 — 评级/对接顺畅、内部稳定;难 — 需维护映射与版本,有持续成本。

### ADR-005 本地运行(Docker Compose 起步,k3s 仅作进阶)
- **状态**:Accepted
- **上下文**:个人学习项目,本地运行、不买服务器域名,无需重 k8s。
- **决策**:Docker Compose 本地起全套中间件;k3s 仅作了解,不强制。
- **后果**:易 — 简单、零成本、专注业务;难 — 无真实集群经验(可后续补)。

### ADR-006 Keycloak 作为统一 IAM
- **状态**:Accepted
- **上下文**:医院多角色(医/护/药/收/管)、需 SSO 与等保审计。
- **决策**:Keycloak 提供 OIDC/RBAC/审计。已加入 docker-compose(Keycloak 24),双 Spring profile(iam/!iam)切换,五权分立 RBAC 已实现(见 ADR-012)。
- **后果**:易 — 集中鉴权审计;难 — 额外运维一个组件,需高可用部署。

### ADR-007 单体阶段中间件范围(拒绝微服务期中间件)
- **状态**:Accepted
- **上下文**:本地 `docker-dev` 已装 Nacos/Seata/RocketMQ/MongoDB 等微服务生态组件,有「顺手就用」之诱。
- **决策**:单体阶段仅引入必需中间件(PostgreSQL / Redis / MinIO / Keycloak / RabbitMQ / xxl-job);Nacos 服务发现、Seata、MongoDB、双 MQ 暂不引入。
- **后果**:易 — 运维面小、故障域小、与 docker-deploy 一致;难 — 未来若抽微服务需补 Seata/Nacos(但那时才需要,可逆)。

### ADR-008 日志存储:Loki + PostgreSQL 审计,不引入 MongoDB
- **状态**:Accepted
- **上下文**:用户问日志系统是否需 MongoDB。日志的核心诉求是高吞吐写入、标签/全文检索、聚合、留存轮转、可视化;而等保三级要求的审计日志还需防篡改与长期取证。MongoDB 是通用文档库,既非检索引擎也非审计存储,为日志引入它只会增加一个要备份/加固/打补丁的数据面,无架构收益。
- **决策**:
  - 应用日志 → **Loki**(标签检索、Grafana 原生、比 ES 省资源);
  - 指标 → **Prometheus**;链路追踪 → **OTel**;
  - **审计日志 → PostgreSQL**(追加写 + 哈希链防篡改 + 长期留存,与业务同库易合规取证)。
  - 明确**不引入 MongoDB** 做日志。
- **后果**:易 — 不新增文档库运维面;审计与业务共享事务与备份策略,合规取证简单;难 — Loki 检索能力弱于 ES(若 P2 病历强检索需求上来,再上 ES 做病历检索,与 Loki 分工不冲突)。

### ADR-009 微服务 vs 模块化单体再确认(含抽出触发条件)
- **状态**:Accepted
- **上下文**:用户再次询问是否应直接做成微服务。重申约束:单医院、团队 < 10、绿地、院内私有化。微服务在此处的分布式税(分布式事务、N 套 CI/CD、网络分区故障、跨服务观测)远高于其独立伸缩收益;且小团队无法按域组建多支运维团队(Conway 定律)。
- **决策**:维持 **模块化单体**。同时定义「何时才抽微服务」的明确触发条件,满足任一即启动 strangler fig 抽出,不重写:
  1. 团队扩张至 **25–30+** 且希望按域并行交付;
  2. 某域需**独立伸缩**(如影像 AI 推理、互联网医院公网高并发);
  3. 某域跨越**硬网络安全边界**(互联网医院置于 DMZ、医保网关独立部署);
  4. 某域**发布节奏**与其余模块显著不同。
- **后果**:易 — 当下零分布式税、强一致、运维轻、日志集中(单应用单日志流,无需跨服务日志管线);难 — 单部署故障面(已用健康检查/降级缓解);不抽则失去独立伸缩(但仅在触发条件出现时才真正需要)。

---

### 业务流程

B 端(门诊就诊)和 C 端(体检预约)的完整业务流程、岗位职责、权限矩阵详见独立文档 [`docs/business-flow.md`](business-flow.md)。

---

## 十一、长期演进路线图(见配图)

- **P0 基础(0–3 月)**:模块化骨架 + 平台基础(认证/EMPI/主数据/审计);**Spring @Scheduled 调度**;临床域 门诊 EMR MVP;私有化部署;等保基线。
- **P1 核心域(3–9 月)**:住院 EMR/护理、医技(经 HL7 接 LIS/PACS)、运营(挂号/收费/医保);集成模块成型;RabbitMQ 事件外置化。
  - 药事 ✓、医技(检验) ✓、报告 ✓、**体检预约 C 端 ✓(套餐 / 号源 / 预约 / 排队 / 患者报告)** 已完成。
- **P2 评级与开放(9–18 月)**:FHIR facade(互联互通测评)、电子病历评级数据结构、BI/读模型(CQRS-lite)、**可选 Elasticsearch 病历检索**、可选互联网医院。
  - **CQRS-lite 读模型 ✓** — 优化就诊列表查询性能
  - **FHIR Facade ✓** — Patient/Encounter/Condition 只读 API
  - **结构化电子病历 ✓** — JSONB 存储半结构化病历数据
- **P3 演进提取(18 月+)**:仅当某模块有独立伸缩/网络边界需求时,按 strangler fig 抽出为独立服务(如互联网医院置于 DMZ 独立部署),不重写。

---

## 十二、主要风险与缓解

| 风险 | 缓解 |
|---|---|
| 模块化单体退化成大泥球 | ArchUnit 红线 + Code Review 守门 |
| 单部署故障影响全院 | 健康检查、蓝绿、DB 主从、非核心降级 |
| 等保测评不通过 | 合规内建(审计/加密/五权分隔),提前对标条款 |
| 互联互通对接返工 | 集成模块防腐层 + FHIR 前置设计 |
| 团队扩张后想拆微服务却拆不动 | 边界从第一天就清晰,抽服务成本低 |

---

## 十三、开源与作品集定位(本项目第二目标)

> 本项目是个人学习 + 求职作品集:本地运行,代码托管于个人 GitHub 仓库便于管理 / 备份 / 展示。重点是把医院域业务、DDD、合规设计、微服务 / 事件驱动 / 网关等当年没学透的东西补全。

### 13.1 仓库与数据好习惯(即使个人项目也建议)
- 不提交密钥 / 真实凭证,用 `.env.example` + `gitignore`(养成职业习惯,将来接真项目无缝迁移)。
- 本地本就是练习数据(合成 / mock),无真实患者隐私,但仍避免把任何真实信息写进仓库。
- 等保 / 五权分隔 / 审计作为**设计深度**体现在架构里,体现你理解医疗系统的特殊要求。

### 13.2 作品集友好架构(混合作品集,见配图)
为你补回实习落下的微服务 / 分布式经验,同时在简历上覆盖「正确判断力」与「微服务 / 分布式能力」,采用:
- **模块化单体核心**:临床 / 医技 / 药事 / 运营 / 集成 / 平台基础,单部署,临床事务单库 ACID(体现「何时不拆」的判断)。
- **抽出 2 个天然独立服务**(经 RabbitMQ 事件驱动):
  1. **通知服务**:SMS / 邮件 / 站内信,无临床事务,事件驱动;
  2. **文件服务**:附件 / 影像上传/下载,包装 MinIO,可独立伸缩。
- **API Gateway**(Spring Cloud Gateway)统一入口,体现网关 / 路由 / 鉴权前置。
- 收益:既守住临床一致性,又能在面试中展示服务划分、事件驱动、网关、容器化全链路——比「硬上全套微服务」更显工程成熟度。

### 13.3 工程实践 / CI
- 用 **GitHub Actions** 做 CI:build + test + ArchUnit 架构守护 + (可选)Sonar 覆盖率门禁——既是好习惯,也是简历上的「CI/CD」素材。
- 本地 `docker compose up` 一键起全套中间件,专注业务代码。
- 仓库标配:`README`(架构图 + ADR 索引 + 启动说明)、`LICENSE`、`/docs/adr`。

### 13.4 简历关键词(供提炼)
Spring Boot 3 + Java 21 · Spring Modulith · ArchUnit 架构守护 · DDD 限界上下文 · CQRS-lite · HL7 v2 / FHIR R4 防腐层 · 等保三级合规设计(审计 / 五权分隔 / EMPI) · 事件驱动(RabbitMQ) · API Gateway · Docker Compose / k3s 私有化 · GitHub Actions CI · 高单测覆盖。

### ADR-010 作品集采用「模块化单体核心 + 2 抽出服务」混合形态
- **状态**:Accepted
- **上下文**:项目第二目标是上传 GitHub 并写入 Java 求职简历;纯模块化单体虽正确但简历上「微服务 / 分布式」关键词偏弱,纯微服务又与本院约束相悖。
- **决策**:核心域保持模块化单体(单库 ACID),仅抽出通知、文件两个天然无临床事务的服务,经 RabbitMQ 事件驱动,前置 API Gateway;公开仓库用合成数据 + GitHub Actions。
- **后果**:易 — 同时覆盖「判断力」与「微服务 / 事件驱动 / 网关」能力,面试素材丰富;难 — 比纯单体多维护 2 个服务与事件契约(但都是低风险、无分布式事务的模块,成本可控)。

### ADR-011 前端采用 Vue 3 + TypeScript + Vite(而非 React)
- **状态**:Accepted
- **上下文**:前端栈选型;用户技术栈与简历关键词均为 Vue 3 + Element Plus,且医院中后台场景以表格 / 表单 / 弹窗为主。React 仅出现在本地 `docker-deploy` 旧模板(其 `web` 镜像是 React build + nginx),并非本设计的选型依据。
- **决策**:前端统一采用 **Vue 3 + TypeScript + Vite + Element Plus + Vue Router + Axios**;`hospital-web` 经 Vite dev proxy 把 `/api` 转发至 API Gateway(:8104),与部署形态一致。
- **后果**:易 — 对齐用户既有积累、简历关键词一致、Element Plus 中后台组件开箱即用、出活快、组合式 API 学习曲线平缓;难 — 放弃了 React 生态(函数式范式 / Hooks / 大厂岗位略多)的练习机会。若将来需补 React 经验,建议另起独立 demo,不混入本作品集以免定位发散。

### ADR-012 认证采用 Keycloak(OIDC) + 五权分立 RBAC,且默认关闭(iam profile 切换)
- **状态**:Accepted
- **上下文**:本项目定位为学习 / 作品集,目标是把"统一认证 + 细粒度授权 + 五权分隔(录入/审核/执行/收费/管理)"作为可演示能力。需避免两个极端:① 裸奔无认证(无工程深度);② 认证写死导致"不开 Keycloak 就跑不起来"(本地联调痛苦)。
- **决策**:
  1. **Authorization Server**:Keycloak 24(已在 docker-compose 中配置,自动导入 `keycloak/hospital-realm.json`)。
  2. **资源服务**:Gateway 与各后端模块均为 OAuth2 Resource Server,校验 JWT;Spring `iam` profile 激活才加载 `SecurityConfig`,默认(`!iam` profile 的 `DevSecurityConfig`)不校验。
  3. **五权分隔 → RBAC**:`visit:entry / visit:audit / order:execute / charge:pay / system:admin / patient:booking` 五个"权"作为 authority;岗位角色 `doctor/nurse/pharmacist/cashier/admin` 为 Keycloak 复合角色,自动展开出对应"权"。后端方法级 `@PreAuthorize("hasAuthority('visit:entry')")` 等;前端同源 authority 控制按钮。
  4. **令牌透传**:Gateway `TokenRelay` 把 JWT 转发下游,下游各自再校验一次(纵深防御)。
  5. **前端**:Keycloak SPA(PKCE)登录,`Bearer` 经 Axios 拦截器附加;路由守卫 `requiresAuth`;开发态 `VITE_AUTH_ENABLED=false` 模拟全权限用户。
  6. **当前用户身份(业务主键)解析**:授权用 authorities,但"我是谁"的业务主键(如患者 `username`)必须**显式从 JWT claim 取**,**禁止**在业务代码用 `auth.getName()` 当业务主键——iam 态 `JwtAuthenticationToken.getName()` 默认返回 `sub`(Keycloak UUID)而非 `preferred_username`。统一经 `CurrentUserResolver.resolveUsername(request)` 取 `preferred_username`(非 iam 态降级从 Bearer 头解析)。详见 **ADR-020**。
- **后果**:易 — 拿到生产级认证/授权范式、五权分隔可演示、前后端权限同源、且默认态零依赖可跑、可平滑切换;难 — 多了一个中间件(Keycloak)的运维认知成本,且 `KeycloakJwtAuthConverter` 因模块隔离在 core/gateway/notification/file 各复制一份(刻意不为之引 `hospital-common` 模块,避免过早抽象)。当前用户身份(业务主键)解析约定见 **ADR-020**。
- **五权分立演示**:`doctor01`(录入+审核)能新建就诊但结算 403;`cashier01`(收费)能结算但新建 403——直观体现 医师不能代收费员结算 。

### ADR-013 前端引入 Pinia 状态管理 + 中后台多页布局
- **状态**:Accepted
- **上下文**:C 阶段目标——把作品集前端从"两页 demo"升级为"有架构感的中后台应用",提升前端工程度(depth),与简历中"Vue3 + TS"技术栈相互印证。原 `hospital-web` 仅有门诊列表/详情两页,状态散在各组件(`reactive`),无统一状态管理与布局框架。
- **决策**:
   1. **状态管理**:引入 **Pinia**。认证状态从模块级 `reactive` 迁移为 `useAuthStore`(保留 `getToken/hasAuthority` 等兼容导出,降低改动面);新增 `useVisitStore`(临床就诊)、`useMenuStore`(菜单树)、`usePatientStore`(患者域)、`useDispatchStore`(排队看板)、`useNotificationStore`(事件列表)五个领域 store。视图只渲染 + 触发动作,状态与异步逻辑收敛到 store。
  2. **布局**:新增 `layouts/MainLayout.vue`(Element Plus `el-container` + `el-aside` 侧边栏菜单 + `el-header` 顶栏用户信息/角色),所有页面以嵌套路由挂在主布局下;侧边栏用 `el-menu` `router` 模式,`meta.title` 驱动顶栏标题。
   3. **多页路由**:路由拆分为 工作台(Dashboard)/ 门诊就诊 / 就诊详情 / 消息通知 / 文件管理 / 患者管理 / 体检预约 / 我的预约 / 我的排队 / 我的报告 / 排队看板(Dispatch) / 处方发药 / 处方详情 / 检验申请 / 检验详情 / 报告管理 / 404,共 16 条业务路由 + 全局 NotFound 兜底,`meta.requiresAuth` + 全局守卫。
  4. **页面厚度**:`NotificationView` 展示通知服务留痕的领域事件(事件驱动闭环);`FileView` 经 Gateway 调 MinIO 上传(独立微服务闭环);`DashboardView` 聚合各 store 概览。
  5. **配套后端**:为让通知页"真"有数据,`notification-service` 新增内存版 `NotificationStore`(演示用,非持久化)+ `GET /api/notify/events` 查询接口,消费端落痕。
### ADR-014 C端患者域与体检预约上下文(模块化单体新增 patient / booking 模块)
- **后果**:易 — 前端具备标准中后台工程结构(Pinia + 布局 + 多页),可直接写进简历;状态跨页共享、可测;事件驱动 / 微服务故事在 UI 上闭环;难 — 包体因 Element Plus 全量引入变大(JS chunk >500KB,属后续按需引入 `unplugin-vue-components` 优化点,当前不碰以免过早优化);新增页面需遵循布局约定(刻意不做路由级权限码,权限仍走后端 `@PreAuthorize` + 前端按钮级 `hasAuthority`)。
- **状态**:Accepted
- **上下文**:用户指出当前系统纯 B 端(医护后台),缺少 C 端患者入口,具体诉求是"患者预约体检、排队分发到各体检项";同时确认用户体系此前只覆盖 B 端(Keycloak 员工账号 + 五权),C 端患者身份/注册流是缺口,菜单级权限也未做。
- **决策**:
  1. 在 `hospital-core` 内新增两个 Spring Modulith 包(沿用 clinical/platform 的 DDD 分层:domain / application / infrastructure / api),而非抽独立服务——与 ADR-001/009/010 一致,保持单库事务与低运维;C 端预约与 B 端临床/收费未来会经"任务流入"衔接,强一致更优。
     - `patient`:Patient 聚合(姓名/性别/生日/手机/身份证),`POST /api/patient/register` 按手机号幂等建档。
     - `booking`:ExamPackage(套餐)/ ExamItem(项目)/ Slot(号源)/ Appointment(预约单)。
  2. **号源不超卖**:用数据库原子占号——`SlotMapper.incrementBooked` 执行 `UPDATE booking.slot SET booked = booked + 1 WHERE id = ? AND booked < capacity`,返回受影响行数,0 即满;预约在 `@Transactional` 内先占号后落单,从根上杜绝超卖,不依赖应用层先查后改(有竞态)或乐观锁版本号(需额外字段)。
  3. **用户体系**:Keycloak 同一 realm 增加 `patient` 域角色 + `hospital-patient` 公开客户端 + `patient01` 测试用户(密码 patient01);Gateway 对 `/api/patient/**` 仍路由到 hospital-core,TokenRelay 透传令牌,`BookingController.book` 以 `@PreAuthorize("hasAuthority('patient:booking')")` 收口(iam 态生效,默认态开放便于联调)。注册与 Keycloak 账号开通的衔接(Admin API 适配器)留作后续。
  4. **菜单级权限(轻量起步)**:`MainLayout` 按 `hasAuthority('patient:booking')` 渲染"体检预约(C端)"子菜单,无权限者根本看不到入口——这是菜单级权限的最小可用形态;完整的"后端返回菜单树"方案见后续 ADR-015。
- **后果**:易 — C 端闭环已全部实现:患者自助注册 → 套餐浏览 → 号源预约 → 排队看板;4 个 C 端视图(/patient/booking /patient/appointments /patient/my-queue /patient/my-reports)+ 后端 patient:booking 权限 + 菜单级可见性;原子杜绝超卖;事件驱动排班(见 ADR-016);报告闭环(见 ADR-019)。

### ADR-015 菜单级权限后端驱动(动态菜单树)
- **状态**:Accepted
- **上下文**:ADR-014 仅以前端 `hasAuthority('patient')` 粗筛一个子菜单,菜单项仍硬编码在 `MainLayout`,违背"菜单唯一事实源应在后端"的原则;新增上下文(如排队看板)时前端也要跟着改,耦合。
- **决策**:
  1. `hospital-core` 新增 `iam` 包:`MenuItem`(record: key/title/path/icon/authorities/children, path 为 null 即分组)、`MenuConfig`(静态导航树 = 菜单唯一事实源; `STAFF` 权限集合复用五权 + `system:admin`)、`MenuService`(按 `SecurityContext` 当前用户 authorities **递归裁剪**;无登录主体/匿名时返回完整菜单,兼容本地默认态零摩擦)、`MenuController`(`GET /api/core/iam/menu`,经 Gateway `/api/core/**` 可达)。
  2. 权限语义:节点 `authorities` 为空 = 任意已登录可见;非空 = 需拥有其中至少一个;分组节点只要有任一可见子项即保留。图标以**字符串**下发,前端经 `icon-map` 映射,后端不耦合 UI 组件。
  3. 前端:`types/menu.ts` + `api/menu.ts` + `stores/menu.ts`(Pinia, onMounted 拉取) + `layouts/icon-map.ts` + `components/MenuNode.vue`(递归渲染分组/叶子,自 import 实现递归);`MainLayout` 改为 `<MenuNode :items="menu.menus" />`,移除全部硬编码菜单与 `hasAuthority('patient')` 分支。
- **后果**:易 — 菜单级权限真正后端驱动、单一事实源、可演示 `doctor01`/`patient01`/`admin01` 看到不同菜单树(如 doctor01 看不到文件管理、patient01 只看到工作台+体检预约)、改菜单不动前端、与 ADR-012 五权同源;难 — 多一个后端端点与一次首屏请求(可缓存/随登录刷新);菜单结构外置(YAML/DB)留作后续,当前静态配置已满足学习/作品集需要且可逆。

### ADR-016 排队分发引擎(进程内事件驱动 + CQRS 读模型)
- **状态**:Accepted
- **上下文**:体检预约单生成后,需把各体检项分发到对应工位(station),并支持工位任务的状态推进(PENDING→IN_PROGRESS→DONE)与排队看板;这是 ADR-014 预约单的下游闭环。
- **决策**:
  1. `hospital-core` 新增 `dispatch` 包(沿用 DDD 分层):`ExamTask`(写模型 `dispatch.exam_task`,工位任务 + 状态机)、`QueueBoard`(读模型 `dispatch.queue_board`,CQRS 物化投影,与写模型 1:1)、各自 Mapper、`DispatchService`、`DispatchController`(`GET /api/core/dispatch/board?station=`、`POST /{id}/start`、`POST /{id}/complete`,写操作需 STAFF 权限)。
  2. **事件驱动**:`AppointmentCreatedEvent` 做成**自包含快照**(`patientName` + 各 `ExamItemBrief`,含 `orderNo`/`station`/`durationMin`),由 `BookingService` 在预约提交后发布;下游零回查 booking/patient。`DispatchService` 用 `@TransactionalEventListener` 消费 → 生成各工位 `ExamTask` + `QueueBoard` 投影行;`start/complete` 推进状态并双向同步投影。
  3. **进程内 vs MQ**:采用 **Spring ApplicationEvent** 域内事件总线(非 RabbitMQ),因 dispatch 与 booking 同处 `hospital-core` 一个 JVM,域内事件正是 ADR-001/009 原则;RabbitMQ 是将来把 Dispatch 抽成独立服务时的桥接路径(strangler 式演进),现在上 MQ 属过早分布式。
  4. 前端 `DispatchView` 按 station 分列展示任务卡,支持「开始/完成」推进与工位筛选;菜单新增「排队看板」(STAFF 可见)。
- **后果**:易 — 闭合"预约 → 分发 → 排队 → 看板"全链路,可在同一应用演示事件驱动 + CQRS + DDD,简历含金量高;事件自包含快照使未来抽服务天然防腐(booking→patient 仅经 `PatientService.getName` 公开读 API,守 ADR-003 模块边界);难 — 当前 CQRS 是"写读同库双写"(lite),并非独立读库异步投影;真正的异步投影(抽服务时用 RabbitMQ 事件更新独立读库)留作后续演进点。

### ADR-017 默认(非 iam)态必须显式 permitAll 安全配置
- **状态**:Accepted
- **上下文**:用户本地 `npm run dev` 后全链路 401,且前端报 `getActivePinia() was called but there was no active Pinia`。排查发现两个 bug:① `SecurityConfig` 被 `@Profile("iam")` 独占,而 core/gateway/notification/file 四个服务均引入 `spring-boot-starter-security` + `oauth2-resource-server`;默认(非 iam)态下**没有任何 `SecurityFilterChain` bean**,Spring Boot 自动安全接管 → 全链路要求认证 → 401,与 ADR-012"默认态无 Keycloak 也能跑"的意图自相矛盾。② `main.ts` 在 `app.use(pinia)` 之前调用 `initAuth()`(内部 `useAuthStore()`),Pinia 尚未 active 抛错。
- **决策**:
  1. 为 `hospital-core`(新增 `DevSecurityConfig`)、`hospital-gateway`、`hospital-notification-service`、`hospital-file-service` 各补一个 `@Profile("!iam")` 的 `SecurityFilterChain`(`hospital-core` 命名为 `DevSecurityConfig`,其余三个服务命名为 `SecurityConfig`),`anyRequest().permitAll()` + 关闭 csrf,使默认态真正免鉴权,与前端 dev 态(不带令牌、模拟全权限用户)对齐;iam 态仍由各自 OAuth2 资源服务配置接管。
  2. `main.ts` 改为先 `createApp` + `app.use(pinia)` + `app.use(ElementPlus)`,再 `initAuth()`,最后 `app.use(router)` + `app.mount('#app')`,保证 `useAuthStore()` 调用时 Pinia 已 active。
- **后果**:易 — 默认态零摩擦本地联调(前端不带令牌、后端全放行),与 ADR-012 设计意图一致,可直接 `npm run dev` 跑通;难 — 默认态因未启用 `@EnableMethodSecurity`,`@PreAuthorize` 等方法级注解不生效(仅放行不校验),故五权分立只能在 **iam 态**(开 Keycloak)演示。gateway/notification/file 在 iam 态已各自实现 OAuth2 资源服务配置(`SecurityConfig` + `@Profile("iam")`)。

### ADR-018 AMQP 事件桥接改为默认启用(profile 移除)
- **状态**:Accepted (反转 ADR-018 原版)
- **上下文**:原 ADR-018 将 AMQP 桥接收进 `amqp profile`,但实际增加了理解成本(2 个 profile 组合出 4 种理论模式,实际只关心开/关)。RabbitMQ 已在 docker-compose 默认启动,作为默认基础设施无需 profile 隔离。
- **决策**:移除 `VisitEventAmqpBridge` 上的 `@Profile("amqp")`,AMQP 桥接始终生效。RabbitMQ 连接已配置在主 `application.yml`,broker 不可用时 Spring AMQP 自动重试,不阻塞启动。启动命令简化为默认启动 + 可选 `--spring.profiles.active=iam`(仅认证)。
- **后果**:易 -- 减少一个 profile 维度,默认即全功能(RabbitMQ 就绪后自动连上);难 -- 本地开发需 docker-compose 启动 RabbitMQ(原本就是默认基础设施)。

### ADR-019 报告模块(Report)作为独立限界上下文
- **状态**:Accepted
- **上下文**:临床就诊闭环中,检验/检查结果需整合为结构化报告,支持 DRAFT → PUBLISHED 状态机,并纳入审计日志;报告与就诊同属 `hospital-core` 单体,不独立部署。
- **决策**:
  1. `hospital-core` 新增 `report` 包(DDD 分层):`Report` 实体(`report.record` 表,含 type/title/content/status/DRAFT→PUBLISHED)、`ReportService`、`ReportController`(`GET/POST /api/reports`、`POST /{id}/publish`)。
  2. 写操作以 `@AuditLog` 拦截(CREATE_REPORT / PUBLISH_REPORT),落 `platform.audit_log`;Controller 无 `@PreAuthorize`(默认态开放,iam 态由 SecurityConfig 统一收口)。
  3. 不与 booking/dispatch 模块发生依赖;报告内容以 Markdown 存储,type 区分 LAB(检验报告)/EXAM(检查报告)/CLINICAL(门诊病历)。
- **后果**:易 — 报告结构与状态机清晰、审计完备、独立 schema 为未来抽服务或对接 FHIR 报告文档做准备;难 — 当前报告内容为纯文本 Markdown,未引入结构化报告模板引擎(留作后续)。

### ADR-020 当前用户身份解析约定(从 JWT 取 preferred_username,而非依赖 getName())
- **状态**:Accepted
- **上下文**:C 端 patient 接口(`/patient/me`、`/dispatch/my-queue`)按当前登录用户查自己数据,初版直接 `SecurityContextHolder.getContext().getAuthentication().getName()` 取用户名;在 iam 模式(`--spring.profiles.active=iam`)下 `getName()` 返回 JWT 的 `sub`(Keycloak 用户 UUID),而非登录用户名 `patient01`,导致 `findByUsername(UUID)` 查空 → HTTP 404(前端表现为"当前用户未绑定患者档案")。根因:`KeycloakJwtAuthConverter` 仅把 `realm_access.roles` 映射为 authorities,不设置 principal 名;`JwtAuthenticationConverter` 也未 `setPrincipalClaimName("preferred_username")`。
- **决策**:
  1. 新增 `CurrentUserResolver`(`platform/security` 包,核心内复用):
     - iam 模式:从 `JwtAuthenticationToken.getToken().getClaimAsString("preferred_username")` 取业务用户名(生产正解)。
     - 非 iam 模式:降级从 `Authorization: Bearer` 头用 nimbus `JWTParser` 解析 `preferred_username`(开发态零摩擦,不验签)。
  2. 所有"按当前用户查自己数据"的接口(`me()`、`myQueue()` 等)统一调用 `CurrentUserResolver.resolveUsername(request)`,**禁止**用 `auth.getName()` 当业务主键。
  3. (可选增强)各服务 `SecurityConfig` 给 `JwtAuthenticationConverter` 配 `setPrincipalClaimName("preferred_username")`,使 `getName()` 也返回业务用户名,作为双保险。
- **实施现状**:`PatientController.me()` 与 `DispatchController.myQueue()` 已改用 `CurrentUserResolver`;前端 `stores/patient.ts` 的 `fetchMe()` 已加 catch 防 404 冒泡。**ArchUnit 守护(检测业务接口误用 `getName()` 当业务主键)待补**——建议作为架构红线之一加入 `ArchitectureTest`。
- **后果**:易 — iam/非 iam 两种模式都能正确识别当前登录用户为 Keycloak 用户名(`patient01` 等);难 — 其他已用 `getName()` 当业务主键的老代码需逐一体检;可选增强需回归 4 个服务的 JWT 解析语义。


### ADR-021 就诊医嘱可修改 / 取消(医嘱纠偏与撤回)
- **状态**:Accepted
- **上下文**:医生在就诊详情页追加医嘱(药品 / 检查 / 检验)后,发现名称 / 数量 / 单价录错或患者不需要某项,当前无法修改只能整条重开,不符合临床纠偏流程。
- **决策**:
  1. `VisitService.editOrder(VisitId, OrderId, updates)`:只允许修改 `CREATED` 状态的医嘱(已执行或已关联已收费的不动),同步重算 `amount = quantity * unitPrice`,并把关联的 `clinical.charge` 记录中 `payStatus=UNPAID` 的项同步更新 `itemName` + `amount`。
  2. `VisitService.cancelOrder(VisitId, OrderId)`:只允许取消 `CREATED` 状态的医嘱,标记为 `CANCELLED`,并删除关联的 `clinical.charge` `UNPAID` 项(避免误收费)。
  3. 后端 `PUT /{id}/orders/{orderId}` 与 `DELETE /{id}/orders/{orderId}` 需 `visit:entry`,写入审计日志。
  4. 前端 `VisitDetailView` 的医嘱表对 `CREATED` 状态+ `canEntry` 权限暴露「修改 / 取消」按钮;修改走对话框复用 `orderForm` 结构。
  5. 新建就诊成功后前端自动 `router.push()` 到该就诊详情页(`VisitListView` 提交后返回 `created.id`)。
- **后果**:易 — 临床医嘱全生命周期(开立 → 修改 → 取消 → 执行 → 收费)闭环,审计可追溯;与"就诊即入详情"联动,体验顺滑;难 — 收费状态更复杂化(已收费订单不支持修改 / 取消,需退费流程,留作后续)。

### ADR-022 处方与检验申请权限按实际业务流程收口(医生创建→药师发药)
- **状态**:Accepted
- **上下文**:需确定谁有权创建处方和检验申请。临床流程:医生开医嘱后直接创建处方/检验申请,收费员只负责结算,药师只负责发药。
- **决策**:
  1. `PrescriptionController.createFromVisit`:`@PreAuthorize("hasAuthority('visit:entry')")` —— 医生才能从就诊聚合药品医嘱生成处方。
  2. `PrescriptionController.dispense` / `cancel`:`@PreAuthorize("hasAuthority('pharmacy:dispense')")` —— 药师发药 / 取消。
  3. `LabController.createFromVisit`:`@PreAuthorize("hasAuthority('visit:entry')")` —— 医生创建检验申请。
  4. `LabController.submitResults` / `cancel`:`@PreAuthorize("hasAuthority('order:execute')")` —— 护士录入结果 / 取消。
  5. `DepartmentController` / `StaffController`:`@PreAuthorize("hasAuthority('system:admin')")` 类级管控。
  6. 前端 `VisitDetailView` "创建处方" 按钮 `canEntry` 可见(医生);`PharmacyPrescriptionsView` 发药 / 取消按钮 `canExecute` 可见(药师);`LabRequisitionDetailView` 录入结果 / 取消同理。
  7. `BookingController.book` 由 `hasRole("patient")` 改为 `hasAuthority("patient:booking")`,与 ADR-012 新增的第七权(patient:booking)一致。
- **后果**:易 — 权限与临床实际流程完全对齐,可在简历中展示"权限设计贴合业务";难 — 需回归测试角色与 authority 映射(新加 `patient:booking`)。

### ADR-023 JWT 自动刷新(前端 axios 拦截器静默续期)
- **状态**:Accepted
- **上下文**:iam 模式(`--spring.profiles.active=iam`)下 Keycloak JWT 默认有效 5 分钟,过期后后端返回 401;虽有 `onTokenExpired` 回调,但标签页后台仍可能拿到过期令牌。用户被迫手动刷新页面重新登录,体验差。
- **决策**:
  1. `api/http.ts` 响应拦截器检测 401 → 调用 `Keycloak.updateToken(30)` → `syncFromKeycloak()` 同步 Pinia store → 用新 token 重试原请求。
  2. `isRefreshing` + `pendingQueue` 锁保证并发请求只触发一次刷新,刷新中的请求挂起,刷新完成后一起重试。
  3. 刷新失败(如 refresh token 也过期)→ 调 `store.login()` 重定向 Keycloak 重新登录。
  4. 请求拦截器从 `getToken()` 实时取最新 token,避免用闭包旧值。
  5. `stores/auth.ts` 暴露 `keycloak` getter 与 `syncFromKeycloak()` 供拦截器调用。
- **后果**:易 — 用户无感续期,不再因 JWT 过期弹窗/跳转;覆盖标签页后台、网络抖动等 "onTokenExpired" 未覆盖场景;难 — 增加一层 axios 拦截复杂度;刷新失败边界依赖 Keycloak PKCE 流程。

