# 医院信息系统 · 整体架构设计与长期发展规划

> **范围约束**:个人学习项目(模拟单体医院业务域) · 全新绿地 · 本地运行(不上公网/不买服务器域名) · 研发者本人(可 AI 辅助) · DB 由 Oracle 转学 PostgreSQL
> **学习深度目标(了解即可,非强制)**:等保三级 / 电子病历评级 / 互联互通(HL7·FHIR) / 个人信息保护——作为简历深度与知识补强方向,按需渐进,不构成本地项目的硬约束。

> **项目定位**:个人学习 + 求职作品集。本地运行、代码托管于个人 GitHub 仓库(便于管理 / 备份 / 展示),不对外公网部署。目标:补回实习落下的医院域知识、拓展简历广度与深度,并从 Oracle 迁移学习 PostgreSQL。

---

## 一、结论先行

**推荐架构:模块化单体(Modular Monolith),而非微服务。**

一句话理由:在「单家医院 + 团队 < 10 + 数据不出院」的约束下,微服务的分布式成本(运维、事务、网络)远高于其带来的独立伸缩收益;而模块化单体既能从第一天就拿到「边界清晰、可维护、可演进」的好处,又保留了未来按需抽出服务(strangler fig)的退路。

三个核心抓手:
1. **边界内建**:模块按业务域划分,依赖方向由 ArchUnit 在 CI 强制,防止退化成「大泥球」。
2. **互操作前置**:集成模块从第一天就讲 HL7 v2 / FHIR R4,为互联互通评级和医保对接铺路。
3. **合规内建**:认证、审计、EMPI、加密、七权分隔作为平台基础,而非事后补丁。

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
- **保留退路**:模块边界清晰 → 任一热点模块可在后续按 strangler fig 抽出为独立服务,无需重写。

---

## 四、容器架构(见配图)

**hospital-core**(模块化单体核心,端口 8101)+ **hospital-gateway**(API 网关,8104)+ **hospital-notification-service**(通知服务,8102)+ **hospital-file-service**(文件服务,8103),共 4 个 Spring Boot 进程。hospital-core 内部 10 个 domain 模块:**临床 / 药事 / 医技(检验) / 报告 / 体检预约 / 排队分发 / 患者 / IAM(菜单) / 组织架构 / FHIR 接口 / 平台基础(JWT+RBAC+审计)**。
配套中间件(均已在 `docker-compose.yml` 中配置,`docker compose up` 全量启动):**PostgreSQL 16**、**Redis 7**、**MinIO**(对象存储)、**RabbitMQ 3**(可靠事件)。调度使用 Spring 内置 `@Scheduled`。
> 认证为后端自管 JWT(JwtTokenService + JwtAuthFilter),无外部 IdP 依赖。
对外:经集成模块对接 **医保网关、区域全民健康平台、第三方 LIS/PACS**(P2 阶段)。

---

## 五、模块边界与依赖规则

- 模块边界由 **ArchUnit** 在 CI 强制(当前已有分层规则 + 一条 `clinical` 单向隔离规则;全代码库未使用 Spring Modulith 的 `@NamedInterface`,Modulith 仅作依赖引入)。应用服务间只能经对外发布的 API 调用,禁止跨模块直接访问 DAO。
  - 10 个 domain 模块分属独立 schema,`iam` 模块菜单数据存储在 `platform.menu` + `platform.menu_authority` 表;RBAC(角色↔权限映射)入 `platform.role` + `platform.role_authority` 表,管理员后台可配。
- 用 **ArchUnit** 在 CI 写规则(当前已有分层规则 + clinical 模块隔离规则),违例即构建失败;模块隔离范围目前仅覆盖 clinical→其他 单向,其余模块间依赖(如 booking→patient)守护待补。
- **共享内核最小化**:仅「平台基础(审计/Redis/域事件基础设施/JWT/RBAC)」为共享内核,所有模块可依赖;`iam` 为菜单模块,菜单数据存 DB,其余模块零共享。
- 数据库:每模块通过**独立 schema**(如 `booking`、`clinical`、`pharmacy`)逻辑隔离(同一 PostgreSQL 实例);单一事实源脚本 `hospital-core/src/main/resources/db/schema.sql` 既由容器 `docker-entrypoint-initdb.d` 挂载在首次启动时建库建表,又由应用层 `spring.sql.init.mode=always` 每次启动幂等重跑做二次保障。尚无需 Flyway/Liquibase。

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
| `platform` | `audit_log` / `sys_user` / `sys_user_role` / `role` / `role_authority` / `menu` / `menu_authority` | 共享内核 |
| `clinical` | `visit` / `orders` / `charge` / `registration` / `visit_read_model` / `medical_record` | 临床就诊 |
| `patient` | `patient` | 患者注册 |
| `booking` | `exam_package` / `exam_item` / `slot` / `appointment` | 体检预约 |
| `dispatch` | `exam_task` / `queue_board` | 排队分发 |
| `pharmacy` | `prescription` / `prescription_item` | 药事 |
| `lab` | `requisition` / `result_item` | 检验 |
| `report` | `record` | 报告 |
| `org` | `department` / `staff` | 组织架构 |

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
| 后端 | Java 21 + Spring Boot 3 | 模块边界由 ArchUnit 强制,域内事件走标准 `ApplicationEventPublisher` |
| ORM | MyBatis-Plus | 团队已精通 |
| DB | PostgreSQL 16(或 MySQL 8) | 见 ADR-002;docker-deploy 已用 PG16 |
| 前端 | Vue 3 + TS + Element Plus + Vite | 临床/管理工作站;经 Vite dev proxy 转发 |
| IAM | 自管 JWT(JwtTokenService + JwtAuthFilter) | 七权分立 RBAC(visit:entry/visit:audit/order:execute/pharmacy:dispense/charge:pay/system:admin/patient:booking),角色/权限入库可调,无外部 IdP 依赖 |
| 缓存 | Redis 7 | 套餐列表查询已接入 Spring Cache(@Cacheable);候选:号源/排队号 |
| 对象存储 | MinIO | 影像/附件;本地已装 |
| 消息 | RabbitMQ(单体阶段唯一 broker) | 可靠事件外置;勿与 RocketMQ 双跑 |
| 调度 | Spring @Scheduled | SlotGenerateJob(每天 3:00 号源生成) + AppointmentCleanupJob(每天 4:00 过期清理),零外部依赖 |
| 部署 | Docker Compose(复用 docker-deploy 模式) → k3s | 拒绝重 k8s;dev/prod 覆盖 + 健康检查 + 非 root |
| 可观测 | Prometheus + Grafana + Loki + OTel | 日志→Loki;指标→Prometheus;追踪→OTel;**审计日志→PostgreSQL(等防篡改)**;不引 MongoDB |
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

> **结论**:`docker-compose.yml` 已包含全部 4 个中间件(`postgres + redis + minio + rabbitmq`),`docker compose up` 一键全量启动,前后端在本地 IDE 运行;前端为 Vue 3,经 Vite dev proxy 把 `/api` 转发至 API Gateway(:8104)。

---

## 九、质量属性

- **可用性(目标 99.9%,7×24)**:单部署风险用健康检查 + 蓝绿发布 + DB 主从(Patroni)缓解;非核心模块优雅降级。
- **安全(学习深度)**:审计日志、静态/传输加密、七权分隔、RBAC——理解医疗系统安全要求;本地用自管 JWT + 统一账号体系(手机号+密码+多角色),不堆 WAF/网络分区。
- **可维护性**:模块边界 + ArchUnit 红线,新人不踩跨域坑。
- **可扩展**:垂直 + 只读副本;热点模块按 strangler fig 抽服务。

---

## 十、架构决策记录

详见 [ADR 目录](adr/README.md)，包含所有架构决策的完整记录（ADR-001 ~ ADR-025）。

> **注**:本章原内联包含全部 ADR 条目及「开源与作品集定位」章节，已拆分至独立文件以保持主文档聚焦核心架构设计。历史决策请查阅 `docs/adr/` 目录。

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
  - **FHIR Facade ✓** — Patient/Encounter/Condition/CapabilityStatement 只读 API
  - **结构化电子病历 ✓** — JSONB 存储半结构化病历数据
- **P3 演进提取(18 月+)**:仅当某模块有独立伸缩/网络边界需求时,按 strangler fig 抽出为独立服务(如互联网医院置于 DMZ 独立部署),不重写。

---

## 十二、主要风险与缓解

| 风险 | 缓解 |
|---|---|
| 模块化单体退化成大泥球 | ArchUnit 红线 + Code Review 守门 |
| 单部署故障影响全院 | 健康检查、蓝绿、DB 主从、非核心降级 |
| 等保测评不通过 | 合规内建(审计/加密/七权分隔),提前对标条款 |
| 互联互通对接返工 | 集成模块防腐层 + FHIR 前置设计 |
| 团队扩张后想拆微服务却拆不动 | 边界从第一天就清晰,抽服务成本低 |

---

## 附录：已拆分内容索引

以下内容已从本文档移除，详见对应独立文件：

- **架构决策记录 (ADR-001 ~ ADR-025)** → [`docs/adr/`](adr/README.md)
- **开源与作品集定位** → 已归档至 ADR-010，详见 [`docs/adr/010-hybrid-architecture.md`](adr/010-hybrid-architecture.md)
