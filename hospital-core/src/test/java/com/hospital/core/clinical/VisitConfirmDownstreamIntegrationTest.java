package com.hospital.core.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.platform.job.DownstreamDocReconcileJob;

/**
 * R-64 端到端不变式:「就诊单已确单 ⇒ 每条 LAB 医嘱都被某个检验申请的明细覆盖」。
 *
 * <p><b>为什么必须是不带 {@code @Transactional} 的集成测试</b>:
 * <ol>
 *   <li>单元测试证明不了 Spring 接线 —— 事件监听器是
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW},
 *       若测试事务从不提交,监听器<b>根本不会触发</b>,单测里"绿"的假象会掩盖真实运行时的失败;</li>
 *   <li>对账任务里的那段 {@code @Select}(LEFT JOIN 找未覆盖医嘱)只有连真库才能验证
 *       —— 列名/表名写错在编译期毫无征兆。</li>
 * </ol>
 * 因此本测试自造数据(唯一手机号,不依赖 schema.sql / DataInitializer 的种子数据),
 * 并在 {@link #cleanUp()} 里按 visitId 自清理,保证可重复运行(R-49 的做法)。
 */
@SpringBootTest
class VisitConfirmDownstreamIntegrationTest {

    private static final String TEST_PHONE_PATIENT = "13900000071";
    private static final String TEST_PHONE_DOCTOR = "13900000072";

    @Autowired VisitService visitService;
    @Autowired OrderMapper orderMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DownstreamDocReconcileJob reconcileJob;

    private Long patientId;
    private Long doctorId;
    private Long visitId;

    @BeforeEach
    void setUp() {
        cleanUp(); // 上次异常退出可能残留(手机号唯一,先清再建)
        patientId = jdbcTemplate.queryForObject(
                "INSERT INTO patient.patient (name, gender, birthday, phone, username) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "R64下游单据测试患者", "M", Date.valueOf("1990-01-01"),
                TEST_PHONE_PATIENT, TEST_PHONE_PATIENT);
        doctorId = jdbcTemplate.queryForObject(
                "INSERT INTO org.staff (name, gender, phone, dept_id, position, username) "
                        + "VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, "R64下游单据测试医生", "M", TEST_PHONE_DOCTOR, null, "DOCTOR", TEST_PHONE_DOCTOR);

        Visit visit = new Visit();
        visit.setPatientId(patientId);
        visit.setDoctorId(doctorId);
        visit.setChiefComplaint("R-64 集成测试");
        visitId = visitService.create(visit).getId();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        if (visitId != null) {
            jdbcTemplate.update("DELETE FROM lab.result_item WHERE requisition_id IN "
                    + "(SELECT id FROM lab.requisition WHERE visit_id = ?)", visitId);
            jdbcTemplate.update("DELETE FROM lab.requisition WHERE visit_id = ?", visitId);
            jdbcTemplate.update("DELETE FROM pharmacy.prescription_item WHERE prescription_id IN "
                    + "(SELECT id FROM pharmacy.prescription WHERE visit_id = ?)", visitId);
            jdbcTemplate.update("DELETE FROM pharmacy.prescription WHERE visit_id = ?", visitId);
            jdbcTemplate.update("DELETE FROM clinical.charge WHERE visit_id = ?", visitId);
            jdbcTemplate.update("DELETE FROM clinical.orders WHERE visit_id = ?", visitId);
            jdbcTemplate.update("DELETE FROM clinical.visit_read_model WHERE visit_id = ?", visitId);
            jdbcTemplate.update("DELETE FROM clinical.visit WHERE id = ?", visitId);
            // R-64: 生成改为事件驱动后,审计由监听器/对账任务显式补写,本测试也会留下记录
            jdbcTemplate.update("DELETE FROM platform.audit_log WHERE target = ?", "visit_id=" + visitId);
            visitId = null;
        }
        jdbcTemplate.update("DELETE FROM org.staff WHERE phone = ?", TEST_PHONE_DOCTOR);
        jdbcTemplate.update("DELETE FROM patient.patient WHERE phone = ?", TEST_PHONE_PATIENT);
    }

    /** 构造一条待落库的检验医嘱(不写库)—— 交给 visitService.addOrder 去插入。 */
    private Order buildLabOrder(String itemName) {
        Order o = new Order();
        o.setVisitId(visitId);
        o.setType("LAB");
        o.setStatus("CREATED");
        o.setItemName(itemName);
        o.setQuantity(1);
        o.setUnitPrice(new BigDecimal("10.00"));
        o.setAmount(new BigDecimal("10.00"));
        o.setExecutionDeptId(5L);
        return o;
    }

    /**
     * 直接把医嘱写库(绕过 addOrder,故也不会触发 R-64 事件)—— 用于模拟
     * "确单时医嘱已存在"这一前提:确单本身是按库里的医嘱分桶发事件的。
     */
    private Order newLabOrder(String itemName) {
        Order o = buildLabOrder(itemName);
        orderMapper.insert(o);
        return o;
    }

    private int requisitionCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM lab.requisition WHERE visit_id = ?", Integer.class, visitId);
        return n == null ? 0 : n;
    }

    private int coveringItemCount(Long orderId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM lab.result_item WHERE order_id = ?", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    /**
     * 统计指定审计记录数。审计写入走专用线程池(异步,R-44),故这里短暂轮询等待 ——
     * 直接断言会因竞态而随机失败。
     */
    private int auditCount(String action, String target) {
        for (int i = 0; i < 30; i++) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform.audit_log WHERE action = ? AND target = ?",
                    Integer.class, action, target);
            if (n != null && n > 0) {
                return n;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return 0;
    }

    /** 取指定审计记录的明细(先等异步写入落库)。 */
    private java.util.List<String> auditDetails(String action, String target) {
        auditCount(action, target);
        return jdbcTemplate.queryForList(
                "SELECT detail FROM platform.audit_log WHERE action = ? AND target = ? ORDER BY id",
                String.class, action, target);
    }

    @Test
    @DisplayName("确单时已有检验医嘱 → 事件驱动自动生成检验申请(无需前端再发第二个请求)")
    void confirm_withLabOrder_generatesRequisition() {
        Order labOrder = newLabOrder("血常规");
        assertThat(requisitionCount()).isZero(); // 确单前不应有申请

        visitService.confirm(visitId);

        // 事件监听器是 AFTER_COMMIT + REQUIRES_NEW,确单方法返回时监听器已同步执行完
        // (不是 @Async),故此处无需等待即可断言
        assertThat(requisitionCount()).isEqualTo(1);
        assertThat(coveringItemCount(labOrder.getId())).isEqualTo(1);
        // R-64: 生成不再经 Controller(切面记不到账),审计由监听器显式补写 ——
        // 这条断言就是"审计没有因为架构调整而消失"的证据
        assertThat(auditDetails("CREATE_REQUISITION", "visit_id=" + visitId))
                .containsExactly("触发: 确单; order_ids=[" + labOrder.getId() + "]");
    }

    @Test
    @DisplayName("确单后追加检验医嘱 → 追加到既有申请(这条曾因前端快照 stale 而静默缺失)")
    void confirmThenAddOrder_appendsToExistingRequisition() {
        Order first = newLabOrder("血常规");
        visitService.confirm(visitId);
        assertThat(requisitionCount()).isEqualTo(1);

        // 注意:此处不能预插库 —— addOrder 自己负责插入(预插会撞 orders 主键)。
        // 这条路径正是原先前端"确单后再追加"的等价物,现在改为服务端在 addOrder 里发事件。
        Order second = buildLabOrder("肝功能");
        visitService.addOrder(visitId, second);
        assertThat(second.getId()).isNotNull();

        // 仍是同一张申请,但两个医嘱都被覆盖
        assertThat(requisitionCount()).isEqualTo(1);
        assertThat(coveringItemCount(first.getId())).isEqualTo(1);
        assertThat(coveringItemCount(second.getId())).isEqualTo(1);
        // 两次触发各自留痕,且明细能区分"确单批量"与"确单后追加单条"
        assertThat(auditDetails("CREATE_REQUISITION", "visit_id=" + visitId))
                .containsExactly(
                        "触发: 确单; order_ids=[" + first.getId() + "]",
                        "触发: 确单后追加医嘱; order_ids=[" + second.getId() + "]");
    }

    @Test
    @DisplayName("确单时没有检验医嘱 → 不生成空申请(事件不该为无医嘱的就诊建单)")
    void confirm_withoutLabOrders_generatesNothing() {
        visitService.confirm(visitId);

        assertThat(requisitionCount()).isZero();
    }

    /**
     * R-64 对账兜底:绕过 {@code addOrder}(即绕过事件),直接把医嘱写进库 ——
     * 模拟"确单时事件生成失败 / 数据由其它途径写入"的场景,对账任务必须把它补上。
     * 这条同时验证了 {@code LabResultItemMapper} 里那段 LEFT JOIN SQL 的正确性。
     */
    @Test
    @DisplayName("对账兜底:已确单但医嘱未被申请覆盖 → 任务补建(含对账 SQL 验证)")
    void reconcileJob_repairsUncoveredLabOrder() {
        visitService.confirm(visitId); // 此时无医嘱 → 无申请
        assertThat(requisitionCount()).isZero();

        // 绕过 addOrder 直接写医嘱:不触发事件,制造"缺失"
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO clinical.orders (visit_id, type, status, item_name, quantity, unit_price, amount, execution_dept_id) "
                        + "VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                Long.class, visitId, "LAB", "CREATED", "对账补建用项目", 1,
                new BigDecimal("10.00"), new BigDecimal("10.00"), 5L);
        assertThat(coveringItemCount(orderId)).isZero();

        reconcileJob.execute();

        assertThat(requisitionCount()).isEqualTo(1);
        assertThat(coveringItemCount(orderId)).isEqualTo(1);
        // 补建同样要留痕,且明细标出"对账补建"—— 与人工确单触发的记录可区分
        assertThat(auditDetails("CREATE_REQUISITION", "visit_id=" + visitId))
                .containsExactly("触发: 对账补建(确单时生成失败或数据异常导入)");
    }
}
