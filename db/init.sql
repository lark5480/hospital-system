CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS clinical;

-- 审计日志
CREATE TABLE IF NOT EXISTS platform.audit_log (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    actor       VARCHAR(100),                   -- 操作人
    action      VARCHAR(100),                   -- 操作类型(如:CREATE_VISIT / PAY_CHARGE)
    target      VARCHAR(200),                   -- 操作目标(如:visit_id=42)
    created_at  TIMESTAMP NOT NULL DEFAULT now() -- 创建时间
);

-- 门诊就诊
CREATE TABLE IF NOT EXISTS clinical.visit (
    id               BIGSERIAL PRIMARY KEY,     -- 主键ID
    patient_id       BIGINT,                    -- 患者ID
    doctor_id        BIGINT,                    -- 医生ID
    dept_id          BIGINT,                    -- 科室ID
    chief_complaint  VARCHAR(500),              -- 主诉
    status           VARCHAR(30),               -- 状态:CREATED(草稿) / CONFIRMED(已确单) / IN_PROGRESS / FINISHED
    visit_time       TIMESTAMP,                 -- 就诊时间
    created_at       TIMESTAMP NOT NULL DEFAULT now()  -- 创建时间
);

-- 医嘱(就诊聚合内的实体)
-- 一次就诊可包含多条医嘱:药品 / 检查 / 检验。与就诊同事务落库,体现强一致。
-- 清理旧表(旧名 clinical.order 使用保留关键字 order,导致 PostgreSQL 报语法错误)
DROP TABLE IF EXISTS clinical.order CASCADE;
CREATE TABLE IF NOT EXISTS clinical.orders (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    visit_id    BIGINT,                         -- 关联就诊ID
    type        VARCHAR(30),                    -- 类型:MEDICATION(药品) / EXAM(检查) / LAB(检验)
    item_name   VARCHAR(200),                   -- 项目名称
    quantity    INT,                            -- 数量
    unit_price  NUMERIC(10, 2),                 -- 单价(元)
    amount      NUMERIC(10, 2),                 -- 金额 = quantity * unit_price,由应用层计算后落库
    execution_dept_id BIGINT,                   -- 执行科室(跨科室协作,可为空)
    status      VARCHAR(30)                     -- 状态:CREATED / EXECUTED / CANCELLED
);

-- 收费记录
-- 每笔医嘱在执行时生成一条收费,与就诊同事务落库(强一致,无需 Saga)
CREATE TABLE IF NOT EXISTS clinical.charge (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    visit_id    BIGINT,                         -- 关联就诊ID
    order_id    BIGINT,                         -- 关联医嘱ID(汇总收费时可为空)
    item_name   VARCHAR(200),                   -- 收费项目名称
    amount      NUMERIC(10, 2),                 -- 金额
    pay_status  VARCHAR(30),                    -- 支付状态:UNPAID(未付) / PAID(已付)
    pay_time    TIMESTAMP                       -- 支付时间
);

-- ===================== C 端:患者域 =====================
CREATE SCHEMA IF NOT EXISTS patient;

-- 患者
CREATE TABLE IF NOT EXISTS patient.patient (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    name        VARCHAR(100) NOT NULL,          -- 姓名
    gender      VARCHAR(10),                    -- 性别
    birthday    DATE,                           -- 出生日期
    phone       VARCHAR(20) UNIQUE,             -- 手机号(唯一)
    id_card     VARCHAR(30),                    -- 身份证号
    username    VARCHAR(100) UNIQUE,            -- 用户名(用于 C 端身份绑定)
    user_id     BIGINT,                         -- 关联统一账号(platform.sys_user)
    created_at  TIMESTAMP NOT NULL DEFAULT now() -- 创建时间
);
ALTER TABLE patient.patient ADD COLUMN IF NOT EXISTS username VARCHAR(100);

-- ===================== C 端:体检预约域 =====================
CREATE SCHEMA IF NOT EXISTS booking;

-- 体检套餐
CREATE TABLE IF NOT EXISTS booking.exam_package (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    name        VARCHAR(200) NOT NULL,          -- 套餐名称
    price       NUMERIC(10, 2) NOT NULL DEFAULT 0,  -- 价格(元)
    description VARCHAR(500),                   -- 套餐描述
    created_at  TIMESTAMP NOT NULL DEFAULT now() -- 创建时间
);

-- 体检项目(属于某个套餐)
CREATE TABLE IF NOT EXISTS booking.exam_item (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    package_id  BIGINT NOT NULL,                -- 归属套餐ID
    name        VARCHAR(200) NOT NULL,          -- 项目名称
    station     VARCHAR(100),                   -- 执行科室/站
    order_no    INT NOT NULL DEFAULT 0,         -- 检查顺序号
    duration_min INT                            -- 预计耗时(分钟)
);
ALTER TABLE booking.exam_item ADD COLUMN IF NOT EXISTS order_no INT NOT NULL DEFAULT 0;

-- 号源时段
CREATE TABLE IF NOT EXISTS booking.slot (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    package_id  BIGINT NOT NULL,                -- 关联套餐ID
    exam_date   DATE NOT NULL,                  -- 检查日期
    period      VARCHAR(20) NOT NULL,           -- 时段:AM(上午) / PM(下午)
    capacity    INT NOT NULL DEFAULT 0,         -- 总容量
    booked      INT NOT NULL DEFAULT 0          -- 已预约数
);

-- 预约记录
CREATE TABLE IF NOT EXISTS booking.appointment (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    patient_id  BIGINT NOT NULL,                -- 患者ID
    package_id  BIGINT NOT NULL,                -- 套餐ID
    slot_id     BIGINT NOT NULL,                -- 号源时段ID
    status      VARCHAR(30) NOT NULL DEFAULT 'BOOKED',  -- 状态:BOOKED / COMPLETED / CANCELLED
    created_at  TIMESTAMP NOT NULL DEFAULT now() -- 创建时间
);
-- C端预约付费字段:预约时自动标记 PAID(演示用),金额来自套餐定价
ALTER TABLE booking.appointment ADD COLUMN IF NOT EXISTS pay_status VARCHAR(20) DEFAULT 'UNPAID';
ALTER TABLE booking.appointment ADD COLUMN IF NOT EXISTS pay_amount NUMERIC(10,2);

-- ===================== F 阶段:排队分发域 =====================
CREATE SCHEMA IF NOT EXISTS dispatch;

-- 检查任务
CREATE TABLE IF NOT EXISTS dispatch.exam_task (
    id            BIGSERIAL PRIMARY KEY,        -- 主键ID
    appointment_id BIGINT NOT NULL,             -- 关联预约ID
    patient_id    BIGINT NOT NULL,              -- 患者ID
    package_id    BIGINT NOT NULL,              -- 套餐ID
    station       VARCHAR(100),                 -- 执行科室/站
    item_name     VARCHAR(200),                 -- 检查项目名称
    patient_name  VARCHAR(100),                 -- 患者姓名
    status        VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- 状态:PENDING / CALLING / DOING / DONE
    seq           INT NOT NULL DEFAULT 0,       -- 排队序号
    started_at    TIMESTAMP,                    -- 开始时间
    done_at       TIMESTAMP,                    -- 完成时间
    created_at    TIMESTAMP NOT NULL DEFAULT now()  -- 创建时间
);

-- 排队看板
CREATE TABLE IF NOT EXISTS dispatch.queue_board (
    id            BIGSERIAL PRIMARY KEY,        -- 主键ID
    station       VARCHAR(100),                 -- 执行科室/站
    patient_name  VARCHAR(100),                 -- 患者姓名
    item_name     VARCHAR(200),                 -- 检查项目名称
    status        VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- 状态:PENDING / CALLING / DOING / DONE
    seq           INT NOT NULL DEFAULT 0,       -- 排队序号
    started_at    TIMESTAMP,                    -- 开始时间
    done_at       TIMESTAMP,                    -- 完成时间
    created_at    TIMESTAMP NOT NULL DEFAULT now()  -- 创建时间
);

-- ===================== 表注释 =====================
COMMENT ON TABLE platform.audit_log     IS '审计日志';
COMMENT ON TABLE clinical.visit        IS '门诊就诊';
COMMENT ON TABLE clinical.orders       IS '医嘱(药品/检查/检验)';
COMMENT ON TABLE clinical.charge       IS '收费记录';
COMMENT ON TABLE patient.patient       IS '患者';
COMMENT ON TABLE booking.exam_package  IS '体检套餐';
COMMENT ON TABLE booking.exam_item     IS '体检项目';
COMMENT ON TABLE booking.slot          IS '号源时段';
COMMENT ON TABLE booking.appointment   IS '预约记录';
COMMENT ON TABLE dispatch.exam_task    IS '检查任务';
COMMENT ON TABLE dispatch.queue_board  IS '排队看板';

-- ===================== 列注释 =====================
COMMENT ON COLUMN clinical.visit.id              IS '就诊主键ID';
COMMENT ON COLUMN clinical.visit.patient_id      IS '患者ID';
COMMENT ON COLUMN clinical.visit.doctor_id       IS '医生ID';
COMMENT ON COLUMN clinical.visit.dept_id         IS '科室ID';
COMMENT ON COLUMN clinical.visit.chief_complaint IS '主诉';
COMMENT ON COLUMN clinical.visit.status          IS '就诊状态:CREATED(草稿)/CONFIRMED(已确单)/IN_PROGRESS/FINISHED';
COMMENT ON COLUMN clinical.visit.visit_time      IS '就诊时间';
COMMENT ON COLUMN clinical.visit.created_at      IS '创建时间';

COMMENT ON COLUMN clinical.orders.id         IS '医嘱主键ID';
COMMENT ON COLUMN clinical.orders.visit_id   IS '关联就诊ID';
COMMENT ON COLUMN clinical.orders.type       IS '医嘱类型:MEDICATION(药品)/EXAM(检查)/LAB(检验)';
COMMENT ON COLUMN clinical.orders.item_name  IS '项目名称';
COMMENT ON COLUMN clinical.orders.quantity   IS '数量';
COMMENT ON COLUMN clinical.orders.unit_price IS '单价(元)';
COMMENT ON COLUMN clinical.orders.amount     IS '金额(元)';
COMMENT ON COLUMN clinical.orders.status     IS '医嘱状态:CREATED/EXECUTED/CANCELLED';

COMMENT ON COLUMN clinical.charge.id         IS '收费主键ID';
COMMENT ON COLUMN clinical.charge.visit_id   IS '关联就诊ID';
COMMENT ON COLUMN clinical.charge.order_id   IS '关联医嘱ID';
COMMENT ON COLUMN clinical.charge.item_name  IS '收费项目名称';
COMMENT ON COLUMN clinical.charge.amount     IS '金额(元)';
COMMENT ON COLUMN clinical.charge.pay_status IS '支付状态:UNPAID(未付)/PAID(已付)';
COMMENT ON COLUMN clinical.charge.pay_time   IS '支付时间';

COMMENT ON COLUMN patient.patient.id         IS '患者主键ID';
COMMENT ON COLUMN patient.patient.name       IS '姓名';
COMMENT ON COLUMN patient.patient.gender     IS '性别';
COMMENT ON COLUMN patient.patient.birthday   IS '出生日期';
COMMENT ON COLUMN patient.patient.phone      IS '手机号';
COMMENT ON COLUMN patient.patient.id_card    IS '身份证号';
COMMENT ON COLUMN patient.patient.username   IS '用户名(用于 C 端身份绑定)';
COMMENT ON COLUMN patient.patient.created_at  IS '创建时间';

COMMENT ON COLUMN booking.exam_package.id          IS '套餐主键ID';
COMMENT ON COLUMN booking.exam_package.name        IS '套餐名称';
COMMENT ON COLUMN booking.exam_package.price       IS '价格(元)';
COMMENT ON COLUMN booking.exam_package.description IS '套餐描述';
COMMENT ON COLUMN booking.exam_package.created_at   IS '创建时间';

COMMENT ON COLUMN booking.exam_item.id          IS '项目主键ID';
COMMENT ON COLUMN booking.exam_item.package_id  IS '归属套餐ID';
COMMENT ON COLUMN booking.exam_item.name        IS '项目名称';
COMMENT ON COLUMN booking.exam_item.station     IS '执行科室/站';
COMMENT ON COLUMN booking.exam_item.order_no    IS '检查顺序号';
COMMENT ON COLUMN booking.exam_item.duration_min IS '预计耗时(分钟)';

COMMENT ON COLUMN booking.slot.id         IS '号源主键ID';
COMMENT ON COLUMN booking.slot.package_id IS '关联套餐ID';
COMMENT ON COLUMN booking.slot.exam_date  IS '检查日期';
COMMENT ON COLUMN booking.slot.period     IS '时段:AM(上午)/PM(下午)';
COMMENT ON COLUMN booking.slot.capacity   IS '总容量';
COMMENT ON COLUMN booking.slot.booked     IS '已预约数';

COMMENT ON COLUMN booking.appointment.id         IS '预约主键ID';
COMMENT ON COLUMN booking.appointment.patient_id IS '患者ID';
COMMENT ON COLUMN booking.appointment.package_id IS '套餐ID';
COMMENT ON COLUMN booking.appointment.slot_id    IS '号源时段ID';
COMMENT ON COLUMN booking.appointment.status      IS '状态:BOOKED/COMPLETED/CANCELLED';
COMMENT ON COLUMN booking.appointment.created_at   IS '创建时间';

COMMENT ON COLUMN dispatch.exam_task.id             IS '任务主键ID';
COMMENT ON COLUMN dispatch.exam_task.appointment_id IS '关联预约ID';
COMMENT ON COLUMN dispatch.exam_task.patient_id     IS '患者ID';
COMMENT ON COLUMN dispatch.exam_task.package_id     IS '套餐ID';
COMMENT ON COLUMN dispatch.exam_task.station        IS '执行科室/站';
COMMENT ON COLUMN dispatch.exam_task.item_name      IS '检查项目名称';
COMMENT ON COLUMN dispatch.exam_task.patient_name   IS '患者姓名';
COMMENT ON COLUMN dispatch.exam_task.status         IS '任务状态:PENDING/CALLING/DOING/DONE';
COMMENT ON COLUMN dispatch.exam_task.seq            IS '排队序号';
COMMENT ON COLUMN dispatch.exam_task.started_at     IS '开始时间';
COMMENT ON COLUMN dispatch.exam_task.done_at        IS '完成时间';
COMMENT ON COLUMN dispatch.exam_task.created_at     IS '创建时间';

COMMENT ON COLUMN dispatch.queue_board.id           IS '看板主键ID';
COMMENT ON COLUMN dispatch.queue_board.station      IS '执行科室/站';
COMMENT ON COLUMN dispatch.queue_board.patient_name IS '患者姓名';
COMMENT ON COLUMN dispatch.queue_board.item_name    IS '检查项目名称';
COMMENT ON COLUMN dispatch.queue_board.status       IS '排队状态:PENDING/CALLING/DOING/DONE';
COMMENT ON COLUMN dispatch.queue_board.seq          IS '排队序号';
COMMENT ON COLUMN dispatch.queue_board.started_at   IS '开始时间';
COMMENT ON COLUMN dispatch.queue_board.done_at      IS '完成时间';
COMMENT ON COLUMN dispatch.queue_board.created_at   IS '创建时间';

-- ===================== 药事域 =====================
CREATE SCHEMA IF NOT EXISTS pharmacy;

-- 处方
CREATE TABLE IF NOT EXISTS pharmacy.prescription (
    id              BIGSERIAL PRIMARY KEY,          -- 主键ID
    visit_id        BIGINT,                         -- 关联就诊ID
    patient_id      BIGINT,                         -- 患者ID
    doctor_id       BIGINT,                         -- 开方医生ID
    pharmacist_id   BIGINT,                         -- 发药药师ID(发药时回填)
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- 状态:PENDING / DISPENSING / DISPENSED / CANCELLED
    remark          VARCHAR(500),                   -- 备注(如用药指导)
    created_at      TIMESTAMP NOT NULL DEFAULT now(),-- 创建时间
    dispensed_at    TIMESTAMP                       -- 发药时间
);

-- 处方明细(单个药品项)
CREATE TABLE IF NOT EXISTS pharmacy.prescription_item (
    id              BIGSERIAL PRIMARY KEY,          -- 主键ID
    prescription_id BIGINT NOT NULL,                -- 归属处方ID
    order_id        BIGINT,                         -- 关联临床医嘱ID(clinical.orders.id)
    item_name       VARCHAR(200) NOT NULL,           -- 药品名称
    quantity        INT NOT NULL,                   -- 数量
    unit_price      NUMERIC(10, 2),                 -- 单价
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING'  -- 状态:PENDING / DISPENSED / CANCELLED
);

COMMENT ON TABLE pharmacy.prescription      IS '处方';
COMMENT ON TABLE pharmacy.prescription_item  IS '处方明细(药品项)';
COMMENT ON COLUMN pharmacy.prescription.id            IS '处方主键ID';
COMMENT ON COLUMN pharmacy.prescription.visit_id       IS '关联就诊ID';
COMMENT ON COLUMN pharmacy.prescription.patient_id     IS '患者ID';
COMMENT ON COLUMN pharmacy.prescription.doctor_id      IS '开方医生ID';
COMMENT ON COLUMN pharmacy.prescription.pharmacist_id  IS '发药药师ID';
COMMENT ON COLUMN pharmacy.prescription.status         IS '处方状态:PENDING/DISPENSING/DISPENSED/CANCELLED';
COMMENT ON COLUMN pharmacy.prescription.remark          IS '备注(如用药指导)';
COMMENT ON COLUMN pharmacy.prescription.created_at      IS '创建时间';
COMMENT ON COLUMN pharmacy.prescription.dispensed_at    IS '发药时间';
COMMENT ON COLUMN pharmacy.prescription_item.id                IS '明细主键ID';
COMMENT ON COLUMN pharmacy.prescription_item.prescription_id   IS '归属处方ID';
COMMENT ON COLUMN pharmacy.prescription_item.order_id          IS '关联临床医嘱ID';
COMMENT ON COLUMN pharmacy.prescription_item.item_name          IS '药品名称';
COMMENT ON COLUMN pharmacy.prescription_item.quantity           IS '数量';
COMMENT ON COLUMN pharmacy.prescription_item.unit_price         IS '单价';
COMMENT ON COLUMN pharmacy.prescription_item.status             IS '明细状态:PENDING/DISPENSED/CANCELLED';

-- ===================== C端演示患者(与 patient01 用户绑定) =====================
-- 手机号 13700000000:独立于员工账号(13800000001~7)和管理员(13800000000),避免唯一约束冲突。
-- username = 手机号(与 PatientService.register 约定一致,JWT sub=phone → /me 按 username 反查患者)。
-- DataInitializer 在启动时按 phone 迁移 patient → sys_user 并兜底建账号/角色。
INSERT INTO patient.patient (name, gender, birthday, phone, username)
SELECT '演示患者', 'M', '1990-01-01', '13700000000', '13700000000'
WHERE NOT EXISTS (SELECT 1 FROM patient.patient WHERE username = 'patient01');

-- ===================== 医技域(检验) =====================
CREATE SCHEMA IF NOT EXISTS lab;

CREATE TABLE IF NOT EXISTS lab.requisition (
    id              BIGSERIAL PRIMARY KEY,          -- 主键ID
    visit_id        BIGINT,                         -- 关联就诊ID
    patient_id      BIGINT,                         -- 患者ID
    doctor_id       BIGINT,                         -- 申请医生ID
    technician_id   BIGINT,                         -- 医技人员ID(结果录入时回填)
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- 状态:PENDING / EXECUTED / CANCELLED
    remark          VARCHAR(500),                   -- 备注(如采样要求)
    created_at      TIMESTAMP NOT NULL DEFAULT now(),-- 创建时间
    sampled_at      TIMESTAMP,                       -- 采样时间
    reported_at     TIMESTAMP                        -- 报告时间
);

CREATE TABLE IF NOT EXISTS lab.result_item (
    id              BIGSERIAL PRIMARY KEY,          -- 主键ID
    requisition_id  BIGINT NOT NULL,                -- 归属检验申请ID
    order_id        BIGINT,                         -- 关联临床医嘱ID
    item_name       VARCHAR(200) NOT NULL,           -- 检验项目名称
    result_value    VARCHAR(200),                    -- 检验结果值
    unit            VARCHAR(50),                     -- 单位
    ref_range       VARCHAR(100),                    -- 参考范围
    abnormal_flag   VARCHAR(10),                     -- 异常标记:NORMAL / ABNORMAL
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING'  -- 状态:PENDING / COMPLETED
);

COMMENT ON TABLE lab.requisition      IS '检验申请';
COMMENT ON TABLE lab.result_item      IS '检验结果项';
COMMENT ON COLUMN lab.requisition.id             IS '检验申请主键ID';
COMMENT ON COLUMN lab.requisition.visit_id       IS '关联就诊ID';
COMMENT ON COLUMN lab.requisition.patient_id     IS '患者ID';
COMMENT ON COLUMN lab.requisition.doctor_id      IS '申请医生ID';
COMMENT ON COLUMN lab.requisition.technician_id  IS '医技人员ID';
COMMENT ON COLUMN lab.requisition.status         IS '申请状态:PENDING/EXECUTED/CANCELLED';
COMMENT ON COLUMN lab.requisition.remark         IS '备注';
COMMENT ON COLUMN lab.requisition.created_at     IS '创建时间';
COMMENT ON COLUMN lab.requisition.sampled_at     IS '采样时间';
COMMENT ON COLUMN lab.requisition.reported_at    IS '报告时间';
COMMENT ON COLUMN lab.result_item.id              IS '结果项主键ID';
COMMENT ON COLUMN lab.result_item.requisition_id  IS '归属检验申请ID';
COMMENT ON COLUMN lab.result_item.order_id        IS '关联临床医嘱ID';
COMMENT ON COLUMN lab.result_item.item_name       IS '检验项目名称';
COMMENT ON COLUMN lab.result_item.result_value    IS '检验结果值';
COMMENT ON COLUMN lab.result_item.unit            IS '单位';
COMMENT ON COLUMN lab.result_item.ref_range       IS '参考范围';
COMMENT ON COLUMN lab.result_item.abnormal_flag   IS '异常标记:NORMAL/ABNORMAL';
COMMENT ON COLUMN lab.result_item.status          IS '结果状态:PENDING/COMPLETED';

-- ===================== 组织域(科室 + 员工) =====================
CREATE SCHEMA IF NOT EXISTS org;

-- 科室
CREATE TABLE IF NOT EXISTS org.department (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    name        VARCHAR(100) NOT NULL,          -- 科室名称
    code        VARCHAR(50) UNIQUE,             -- 科室编码
    description VARCHAR(200),                   -- 科室描述
    created_at  TIMESTAMP NOT NULL DEFAULT now() -- 创建时间
);

-- 员工
CREATE TABLE IF NOT EXISTS org.staff (
    id          BIGSERIAL PRIMARY KEY,          -- 主键ID
    name        VARCHAR(100) NOT NULL,          -- 姓名
    gender      VARCHAR(10),                    -- 性别
    phone       VARCHAR(20),                    -- 手机号
    dept_id     BIGINT,                         -- 所属科室ID
    position    VARCHAR(50) NOT NULL,           -- 岗位:DOCTOR / NURSE / PHARMACIST / CASHIER / ADMIN
    username    VARCHAR(100) UNIQUE,            -- 用户名(关联登录账号)
    password    VARCHAR(100),                   -- BCrypt 哈希(为空=老数据兼容,免密登录)
    user_id     BIGINT,                         -- 关联统一账号(platform.sys_user)
    status      VARCHAR(20) DEFAULT 'ACTIVE',   -- 状态:ACTIVE / INACTIVE
    created_at  TIMESTAMP NOT NULL DEFAULT now() -- 创建时间
);

-- ===================== 报告域 =====================
CREATE SCHEMA IF NOT EXISTS report;

CREATE TABLE IF NOT EXISTS report.record (
    id              BIGSERIAL PRIMARY KEY,          -- 主键ID
    visit_id        BIGINT,                         -- 关联就诊ID
    patient_id     BIGINT,                         -- 患者ID(C端报告查询)
    type            VARCHAR(30),                    -- 类型:LAB(检验)/EXAM(检查)/CLINICAL(门诊病历)
    title           VARCHAR(200),                   -- 标题
    content         TEXT,                           -- 报告内容(Markdown)
    doctor_id       BIGINT,                         -- 报告医生ID
    status          VARCHAR(30) NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PUBLISHED
    created_at      TIMESTAMP NOT NULL DEFAULT now(),-- 创建时间
    published_at    TIMESTAMP                       -- 发布时间
);
ALTER TABLE report.record ADD COLUMN IF NOT EXISTS patient_id BIGINT;
COMMENT ON COLUMN report.record.patient_id IS '患者ID(C端报告查询)';

-- 报告 PDF:MinIO 对象名与生成状态
ALTER TABLE report.record ADD COLUMN IF NOT EXISTS file_id VARCHAR(300);
ALTER TABLE report.record ADD COLUMN IF NOT EXISTS pdf_status VARCHAR(20) DEFAULT 'PENDING';
COMMENT ON COLUMN report.record.file_id IS 'PDF 文件在 MinIO 的对象名(reports/{reportId}.pdf)';
COMMENT ON COLUMN report.record.pdf_status IS 'PDF 生成状态:PENDING/READY/FAILED';

COMMENT ON TABLE report.record          IS '报告记录';
COMMENT ON COLUMN report.record.id           IS '报告主键ID';
COMMENT ON COLUMN report.record.visit_id     IS '关联就诊ID';
COMMENT ON COLUMN report.record.type         IS '报告类型:LAB/EXAM/CLINICAL';
COMMENT ON COLUMN report.record.title        IS '标题';
COMMENT ON COLUMN report.record.content      IS '报告内容';
COMMENT ON COLUMN report.record.doctor_id    IS '报告医生ID';
COMMENT ON COLUMN report.record.status       IS '状态:DRAFT/PUBLISHED';
COMMENT ON COLUMN report.record.created_at   IS '创建时间';
COMMENT ON COLUMN report.record.published_at IS '发布时间';

-- ===================== 统一账号 =====================
-- 统一账号表:手机号 + 密码登录,一个账号可绑定多角色
CREATE TABLE IF NOT EXISTS platform.sys_user (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(20) NOT NULL UNIQUE,  -- 登录手机号(唯一)
    password    VARCHAR(100),                 -- BCrypt 哈希
    name        VARCHAR(100),                 -- 显示名(冗余,方便查询)
    status      VARCHAR(20) DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 用户×角色(多对多):一个账号可同时是医生+患者+管理员
CREATE TABLE IF NOT EXISTS platform.sys_user_role (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES platform.sys_user(id) ON DELETE CASCADE,
    role_code   VARCHAR(60) NOT NULL REFERENCES platform.role(code),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(user_id, role_code)
);

-- ===================== RBAC:角色/权限(平台基础) =====================
-- 角色:对应医护岗位,管理员可为其配置可见菜单(authority 列表)
CREATE TABLE IF NOT EXISTS platform.role (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(60) NOT NULL UNIQUE,          -- 角色编码:DOCTOR/NURSE/PHARMACIST/CASHIER/ADMIN
    name        VARCHAR(100) NOT NULL,                -- 角色名称:医师/护士/药师/收费员/管理员
    description VARCHAR(200),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 角色↔菜单权限:一个角色可拥有多个 authority(决定侧边栏菜单可见性 + 后端接口鉴权)
CREATE TABLE IF NOT EXISTS platform.role_authority (
    id          BIGSERIAL PRIMARY KEY,
    role_code   VARCHAR(60) NOT NULL REFERENCES platform.role(code) ON DELETE CASCADE,
    authority   VARCHAR(100) NOT NULL,                -- 如 visit:entry / order:execute / pharmacy:dispense
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(role_code, authority)
);

COMMENT ON TABLE platform.role              IS 'RBAC 角色(对应医护岗位)';
COMMENT ON TABLE platform.role_authority    IS '角色↔菜单权限映射(决定菜单可见性 + 接口鉴权)';
COMMENT ON COLUMN platform.role.code        IS '角色编码:DOCTOR/NURSE/PHARMACIST/CASHIER/ADMIN';
COMMENT ON COLUMN platform.role_authority.authority IS '权限串:visit:entry/visit:audit/order:execute/pharmacy:dispense/charge:pay/system:admin/patient:booking';

-- ===================== 幂等种子数据(每次启动重跑,WHERE NOT EXISTS 防重复) =====================
INSERT INTO booking.exam_package (name, price, description)
SELECT '基础健康套餐', 499.00, '常规内科+血常规+尿常规+B超'
WHERE NOT EXISTS (SELECT 1 FROM booking.exam_package WHERE name = '基础健康套餐');

INSERT INTO booking.exam_package (name, price, description)
SELECT '深度甄选套餐', 1299.00, '含肿瘤标志物+心肺功能+全套影像'
WHERE NOT EXISTS (SELECT 1 FROM booking.exam_package WHERE name = '深度甄选套餐');

INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min)
SELECT p.id, '血常规', '采血室', 1, 5 FROM booking.exam_package p
WHERE p.name = '基础健康套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.exam_item ei WHERE ei.package_id = p.id AND ei.name = '血常规');

INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min)
SELECT p.id, '尿常规', '采集室', 2, 5 FROM booking.exam_package p
WHERE p.name = '基础健康套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.exam_item ei WHERE ei.package_id = p.id AND ei.name = '尿常规');

INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min)
SELECT p.id, '腹部B超', 'B超室', 3, 15 FROM booking.exam_package p
WHERE p.name = '基础健康套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.exam_item ei WHERE ei.package_id = p.id AND ei.name = '腹部B超');

INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min)
SELECT p.id, '肿瘤标志物', '采血室', 1, 5 FROM booking.exam_package p
WHERE p.name = '深度甄选套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.exam_item ei WHERE ei.package_id = p.id AND ei.name = '肿瘤标志物');

INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min)
SELECT p.id, '肺功能', '肺功能室', 2, 20 FROM booking.exam_package p
WHERE p.name = '深度甄选套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.exam_item ei WHERE ei.package_id = p.id AND ei.name = '肺功能');

INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min)
SELECT p.id, '胸部CT', '影像科', 3, 25 FROM booking.exam_package p
WHERE p.name = '深度甄选套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.exam_item ei WHERE ei.package_id = p.id AND ei.name = '胸部CT');

-- 基础套餐号源:上午 capacity=2(可演示正常预约),下午 capacity=1(连约两次即触发"号源已满")
INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked)
SELECT p.id, DATE '2026-07-10', 'AM', 2, 0 FROM booking.exam_package p
WHERE p.name = '基础健康套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.slot s WHERE s.package_id = p.id AND s.exam_date = DATE '2026-07-10' AND s.period = 'AM');

INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked)
SELECT p.id, DATE '2026-07-10', 'PM', 1, 0 FROM booking.exam_package p
WHERE p.name = '基础健康套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.slot s WHERE s.package_id = p.id AND s.exam_date = DATE '2026-07-10' AND s.period = 'PM');

INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked)
SELECT p.id, DATE '2026-07-11', 'AM', 3, 0 FROM booking.exam_package p
WHERE p.name = '深度甄选套餐'
  AND NOT EXISTS (SELECT 1 FROM booking.slot s WHERE s.package_id = p.id AND s.exam_date = DATE '2026-07-11' AND s.period = 'AM');

-- ===================== 组织域种子数据(科室 + 员工) =====================
INSERT INTO org.department (name, code, description)
SELECT '内科', 'INTERNAL', '内科门诊'
WHERE NOT EXISTS (SELECT 1 FROM org.department WHERE code = 'INTERNAL');

INSERT INTO org.department (name, code, description)
SELECT '外科', 'SURGERY', '外科门诊'
WHERE NOT EXISTS (SELECT 1 FROM org.department WHERE code = 'SURGERY');

INSERT INTO org.department (name, code, description)
SELECT '收费处', 'CASHIER', '收费窗口'
WHERE NOT EXISTS (SELECT 1 FROM org.department WHERE code = 'CASHIER');

INSERT INTO org.department (name, code, description)
SELECT '药房', 'PHARMACY', '药房'
WHERE NOT EXISTS (SELECT 1 FROM org.department WHERE code = 'PHARMACY');

-- 员工(关联到科室ID和登录账号)
INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '张医生', 'M', '13800000001', d.id, 'DOCTOR', 'doctor01'
FROM org.department d WHERE d.code = 'INTERNAL'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'doctor01');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '李医生', 'F', '13800000002', d.id, 'DOCTOR', 'doctor02'
FROM org.department d WHERE d.code = 'INTERNAL'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'doctor02');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '王护士', 'F', '13800000003', d.id, 'NURSE', 'nurse01'
FROM org.department d WHERE d.code = 'INTERNAL'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'nurse01');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '赵收费', 'M', '13800000004', d.id, 'CASHIER', 'cashier01'
FROM org.department d WHERE d.code = 'CASHIER'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'cashier01');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '孙收费', 'F', '13800000005', d.id, 'CASHIER', 'cashier02'
FROM org.department d WHERE d.code = 'CASHIER'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'cashier02');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '周药师', 'M', '13800000006', d.id, 'PHARMACIST', 'pharmacist01'
FROM org.department d WHERE d.code = 'PHARMACY'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'pharmacist01');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '吴药师', 'F', '13800000007', d.id, 'PHARMACIST', 'pharmacist02'
FROM org.department d WHERE d.code = 'PHARMACY'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'pharmacist02');

INSERT INTO org.staff (name, gender, phone, dept_id, position, username)
SELECT '系统管理员', 'M', '13800000000', d.id, 'ADMIN', 'admin01'
FROM org.department d WHERE d.code = 'INTERNAL'
  AND NOT EXISTS (SELECT 1 FROM org.staff WHERE username = 'admin01');

-- ===================== RBAC 角色 + 权限种子(幂等) =====================
INSERT INTO platform.role (code, name, description)
SELECT 'DOCTOR', '医师', '门诊就诊/开医嘱/创建处方与申请'
WHERE NOT EXISTS (SELECT 1 FROM platform.role WHERE code = 'DOCTOR');
INSERT INTO platform.role (code, name, description)
SELECT 'NURSE', '护士', '检查执行/检验结果录入'
WHERE NOT EXISTS (SELECT 1 FROM platform.role WHERE code = 'NURSE');
INSERT INTO platform.role (code, name, description)
SELECT 'PHARMACIST', '药师', '处方发药/取消'
WHERE NOT EXISTS (SELECT 1 FROM platform.role WHERE code = 'PHARMACIST');
INSERT INTO platform.role (code, name, description)
SELECT 'CASHIER', '收费员', '结算收费'
WHERE NOT EXISTS (SELECT 1 FROM platform.role WHERE code = 'CASHIER');
INSERT INTO platform.role (code, name, description)
SELECT 'ADMIN', '管理员', '全部权限'
WHERE NOT EXISTS (SELECT 1 FROM platform.role WHERE code = 'ADMIN');
INSERT INTO platform.role (code, name, description)
SELECT 'PATIENT', '患者', '体检预约/查看自身数据'
WHERE NOT EXISTS (SELECT 1 FROM platform.role WHERE code = 'PATIENT');

-- 医师:录入 + 审核 + 执行(检查) + 预约体检
INSERT INTO platform.role_authority (role_code, authority) SELECT 'DOCTOR', 'visit:entry'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='DOCTOR' AND authority='visit:entry');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'DOCTOR', 'visit:audit'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='DOCTOR' AND authority='visit:audit');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'DOCTOR', 'order:execute'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='DOCTOR' AND authority='order:execute');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'DOCTOR', 'patient:booking'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='DOCTOR' AND authority='patient:booking');
-- 护士:执行(检查) + 预约体检
INSERT INTO platform.role_authority (role_code, authority) SELECT 'NURSE', 'order:execute'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='NURSE' AND authority='order:execute');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'NURSE', 'patient:booking'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='NURSE' AND authority='patient:booking');
-- 药师:发药
INSERT INTO platform.role_authority (role_code, authority) SELECT 'PHARMACIST', 'pharmacy:dispense'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='PHARMACIST' AND authority='pharmacy:dispense');
-- 收费员:结算
INSERT INTO platform.role_authority (role_code, authority) SELECT 'CASHIER', 'charge:pay'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='CASHIER' AND authority='charge:pay');
-- 患者:体检预约
INSERT INTO platform.role_authority (role_code, authority) SELECT 'PATIENT', 'patient:booking'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='PATIENT' AND authority='patient:booking');
-- 管理员:全部
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'visit:entry'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='visit:entry');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'visit:audit'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='visit:audit');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'order:execute'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='order:execute');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'pharmacy:dispense'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='pharmacy:dispense');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'charge:pay'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='charge:pay');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'system:admin'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='system:admin');
INSERT INTO platform.role_authority (role_code, authority) SELECT 'ADMIN', 'patient:booking'
WHERE NOT EXISTS (SELECT 1 FROM platform.role_authority WHERE role_code='ADMIN' AND authority='patient:booking');

-- ===================== C端种子报告数据 =====================
INSERT INTO report.record (visit_id, patient_id, type, title, content, doctor_id, status, created_at, published_at)
SELECT null, p.id, 'EXAM', '基础健康体检报告', E'## 体检总结

**一般情况**:体温36.5°C,血压120/80mmHg
**血常规**:未见异常
**尿常规**:未见异常
**腹部B超**:未见异常

**结论**:健康,建议定期复查。', 1, 'PUBLISHED', now(), now()
FROM patient.patient p WHERE p.username = 'patient01'
  AND NOT EXISTS (SELECT 1 FROM report.record r WHERE r.title = '基础健康体检报告');

-- ===================== CQRS-lite 读模型 =====================
-- 就诊读模型：写操作同一事务内同步更新，读查询直接走此表
CREATE TABLE IF NOT EXISTS clinical.visit_read_model (
    id              BIGSERIAL PRIMARY KEY,
    visit_id        BIGINT NOT NULL UNIQUE,
    patient_id      BIGINT,
    doctor_id       BIGINT,
    dept_id         BIGINT,
    chief_complaint VARCHAR(500),
    status          VARCHAR(30),
    visit_time      TIMESTAMP,
    created_at      TIMESTAMP,
    patient_name    VARCHAR(100),
    doctor_name     VARCHAR(100),
    dept_name       VARCHAR(100),
    order_count     INT DEFAULT 0,
    total_amount    NUMERIC(10,2) DEFAULT 0,
    pay_status      VARCHAR(30),
    unpaid_count    INT DEFAULT 0
);

COMMENT ON TABLE clinical.visit_read_model IS '就诊读模型(CQRS-lite)';
COMMENT ON COLUMN clinical.visit_read_model.visit_id IS '关联写模型visit.id';
COMMENT ON COLUMN clinical.visit_read_model.patient_name IS '患者姓名(物化字段)';
COMMENT ON COLUMN clinical.visit_read_model.doctor_name IS '医生姓名(物化字段)';
COMMENT ON COLUMN clinical.visit_read_model.dept_name IS '科室名称(物化字段)';
COMMENT ON COLUMN clinical.visit_read_model.order_count IS '医嘱数量';
COMMENT ON COLUMN clinical.visit_read_model.total_amount IS '总金额';
COMMENT ON COLUMN clinical.visit_read_model.pay_status IS '收费状态:NO_CHARGES/HAS_UNPAID/ALL_PAID';
COMMENT ON COLUMN clinical.visit_read_model.unpaid_count IS '未缴费数量';

-- 索引
CREATE INDEX IF NOT EXISTS idx_visit_rm_patient_name ON clinical.visit_read_model(patient_name);
CREATE INDEX IF NOT EXISTS idx_visit_rm_doctor_name ON clinical.visit_read_model(doctor_name);
CREATE INDEX IF NOT EXISTS idx_visit_rm_chief_complaint ON clinical.visit_read_model(chief_complaint);
CREATE INDEX IF NOT EXISTS idx_visit_rm_visit_time ON clinical.visit_read_model(visit_time DESC);
CREATE INDEX IF NOT EXISTS idx_visit_rm_dept_id ON clinical.visit_read_model(dept_id);

-- ===================== 结构化病历 =====================
CREATE TABLE IF NOT EXISTS clinical.medical_record (
    id              BIGSERIAL PRIMARY KEY,
    visit_id        BIGINT NOT NULL UNIQUE,
    patient_id      BIGINT NOT NULL,
    doctor_id       BIGINT NOT NULL,
    dept_id         BIGINT,

    -- 结构化字段
    chief_complaint     VARCHAR(500),
    present_illness     TEXT,
    past_history        TEXT,
    family_history      TEXT,
    allergy_history     TEXT,

    -- JSONB 字段
    physical_exam       JSONB DEFAULT '{}',
    auxiliary_exam      JSONB DEFAULT '[]',
    diagnosis           JSONB DEFAULT '[]',

    treatment_plan      TEXT,

    -- 元数据
    status              VARCHAR(30) DEFAULT 'DRAFT',
    created_at          TIMESTAMP DEFAULT now(),
    updated_at          TIMESTAMP DEFAULT now(),
    finalized_at        TIMESTAMP
);

COMMENT ON TABLE clinical.medical_record IS '结构化病历(门诊病历)';
COMMENT ON COLUMN clinical.medical_record.visit_id IS '关联就诊ID';
COMMENT ON COLUMN clinical.medical_record.chief_complaint IS '主诉';
COMMENT ON COLUMN clinical.medical_record.present_illness IS '现病史';
COMMENT ON COLUMN clinical.medical_record.past_history IS '既往史';
COMMENT ON COLUMN clinical.medical_record.family_history IS '家族史';
COMMENT ON COLUMN clinical.medical_record.allergy_history IS '过敏史';
COMMENT ON COLUMN clinical.medical_record.physical_exam IS '体格检查(JSONB)';
COMMENT ON COLUMN clinical.medical_record.auxiliary_exam IS '辅助检查(JSONB)';
COMMENT ON COLUMN clinical.medical_record.diagnosis IS '诊断(JSONB)';
COMMENT ON COLUMN clinical.medical_record.treatment_plan IS '治疗计划';
COMMENT ON COLUMN clinical.medical_record.status IS '状态:DRAFT/FINAL';
COMMENT ON COLUMN clinical.medical_record.finalized_at IS '终诊时间';

CREATE INDEX IF NOT EXISTS idx_mr_visit_id ON clinical.medical_record(visit_id);
CREATE INDEX IF NOT EXISTS idx_mr_patient_id ON clinical.medical_record(patient_id);
CREATE INDEX IF NOT EXISTS idx_mr_diagnosis ON clinical.medical_record USING GIN (diagnosis);

-- 初始菜单数据（将由DataInitializer从MenuConfig迁移）
