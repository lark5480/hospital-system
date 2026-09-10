package com.hospital.core.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.core.clinical.application.PageResult;
import com.hospital.core.clinical.application.VisitDetail;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;

/**
 * R-49: 消除对全局种子数据(schema.sql 种下的演示患者 id=1 / DataInitializer 迁移出的账号)的断言耦合。
 *
 * <p>改动:
 * <ul>
 *   <li>患者用 JdbcTemplate 自造(@Transactional 结束后回滚),不再硬编码 {@code patientId=1L};</li>
 *   <li>{@code shouldQueryFromReadModel} 原断言 {@code total==1}(耦合"库里恰好只有 1 条"),
 *       改为"包含本次自造就诊"的包含式断言,配合 {@code total>=1}。</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class VisitReadModelTest {

    @Autowired
    private VisitService visitService;

    @Autowired
    private VisitReadModelMapper readModelMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * R-49: 自造一条测试患者并返回自增 id。测试位于事务中,方法结束即回滚,
     * 故无需 @AfterEach 显式清理(与种子患者 13800000000/13700000000 隔离的独立手机号)。
     */
    private Long newTestPatientId() {
        String phone = "13900000050";
        return jdbcTemplate.queryForObject(
                "INSERT INTO patient.patient (name, gender, birthday, phone, username) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "R49读模型测试患者", "M", Date.valueOf("1990-01-01"), phone, phone);
    }

    @Test
    void shouldRefreshReadModelAfterCreateVisit() {
        // Given
        Visit visit = new Visit();
        visit.setPatientId(newTestPatientId());
        visit.setChiefComplaint("测试主诉");

        Order order = new Order();
        order.setType("MEDICATION");
        order.setItemName("阿莫西林");
        order.setQuantity(2);
        order.setUnitPrice(new BigDecimal("15.50"));

        // When
        VisitDetail detail = visitService.createWithOrders(visit, List.of(order));

        // Then
        var rm = readModelMapper.selectByVisitId(detail.getVisit().getId());
        assertThat(rm).isNotNull();
        assertThat(rm.getOrderCount()).isEqualTo(1);
        assertThat(rm.getTotalAmount()).isEqualByComparingTo(new BigDecimal("31.00"));
        assertThat(rm.getPayStatus()).isEqualTo("HAS_UNPAID");
    }

    @Test
    void shouldQueryFromReadModel() {
        // Given - 创建测试数据。关键字取唯一纯中文短语:listPage 会把关键字 lowercase 后做 LIKE,
        // 而 PostgreSQL 的 LIKE 大小写敏感,故关键字不能含会被改写的 ASCII 字母(否则匹配不上)。
        String keyword = "读模型专属主诉甲";
        Visit visit = new Visit();
        visit.setPatientId(newTestPatientId());
        visit.setChiefComplaint(keyword);
        VisitDetail created = visitService.createWithOrders(visit, List.of());
        Long createdVisitId = created.getVisit().getId();

        // When
        PageResult<VisitDetail> result = visitService.listPage(keyword, 1, 10, null);

        // Then - R-49: 改为包含式断言,不再依赖"全库恰好只有 1 条"
        assertThat(result.getItems())
                .extracting(d -> d.getVisit().getId())
                .contains(createdVisitId);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
    }
}
