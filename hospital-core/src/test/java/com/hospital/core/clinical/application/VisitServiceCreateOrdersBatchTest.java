package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.stream.Collectors.toMap;
import static java.util.function.Function.identity;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;

/**
 * R-21: 医嘱(orders)批量化专项验证 —— <b>真实 PostgreSQL</b>。
 *
 * <p>本用例是本任务唯一真正危险点("charge.order_id 依赖 order 自增主键回填,批量后是否错位")的
 * 实测证据。{@code VisitService.createWithOrders} 现把医嘱与收费各用一条
 * {@code INSERT ... VALUES (...),(...)} 批量写入,医嘱侧通过
 * {@code OrderMapper.insertBatch} 的 {@code @Options(useGeneratedKeys = true, keyProperty = "id")}
 * 把自增主键按序回填到每个 {@link Order}。
 *
 * <p><b>为什么能证明"没有错位"</b>:用例刻意让"医嘱在 list 中的顺序 = item_name 升序 =
 * 生成键(自增 id)升序"。于是:
 * <ul>
 *   <li>若回填按行序正确,则"按 id 升序排列后 item_name 也升序";</li>
 *   <li>若 PG 返回的 RETURNING 行序与 VALUES 顺序不一致 / MyBatis 回填错位,
 *       则 id 升序后 item_name 必然乱序,断言即失败;</li>
 *   <li>再叠加"内存中的 (id, item_name) 与 DB 直读的 (id, item_name) 完全一致",
 *       双重锁定"回填的 id 就是该行真实落库的 id"。</li>
 * </ul>
 *
 * <p>用例位于事务中,方法结束即回滚,无需显式清理,重复执行幂等。
 */
@SpringBootTest
@Transactional
class VisitServiceCreateOrdersBatchTest {

    @Autowired
    private VisitService visitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** R-49 同款做法:自造一条测试患者(独立手机号,避免与种子数据冲突),事务结束即回滚。 */
    private Long newTestPatientId() {
        String phone = "13900000060";
        return jdbcTemplate.queryForObject(
                "INSERT INTO patient.patient (name, gender, birthday, phone, username) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "R21批量医嘱测试患者", "M", Date.valueOf("1990-01-01"), phone, phone);
    }

    private static Order newOrder(int seq) {
        Order o = new Order();
        // 类型轮转,顺带覆盖三类医嘱都能批量落库
        o.setType(switch (seq % 3) {
            case 0 -> "EXAM";
            case 1 -> "MEDICATION";
            default -> "LAB";
        });
        // item_name 按下标升序命名 —— 与生成键升序对齐,用于暴露"错位"
        o.setItemName(String.format("R21医嘱-%02d", seq));
        o.setQuantity(seq);
        o.setUnitPrice(new BigDecimal("10.00").add(BigDecimal.valueOf(seq)));
        return o;
    }

    @Test
    @DisplayName("R-21 createWithOrders: 6 条医嘱单条多值 INSERT,主键按序回填且 charge.order_id 无错位")
    void createWithOrders_batchInsert_backfillsKeysInOrder() {
        int n = 6; // ≥5,越多越能暴露错位
        Visit visit = new Visit();
        visit.setPatientId(newTestPatientId());
        visit.setChiefComplaint("R21 批量医嘱");
        List<Order> orders = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            orders.add(newOrder(i));
        }

        // When:一次建单,医嘱与收费均批量化
        VisitDetail detail = visitService.createWithOrders(visit, orders);
        Long visitId = detail.getVisit().getId();

        // 断言 1:返回的每个 Order.getId() 非 null 且互不相同(证明 key 回填成功)
        List<Order> returned = detail.getOrders();
        assertThat(returned).hasSize(n);
        assertThat(returned).allSatisfy(o -> assertThat(o.getId()).as("order.id 应为自增主键回填值").isNotNull());
        assertThat(returned.stream().map(Order::getId)).doesNotHaveDuplicates();

        // 断言 2:id 与内容一一对应没有错位 —— 按 id 升序后 item_name 必须仍是升序
        List<Order> byId = returned.stream()
                .sorted(Comparator.comparing(Order::getId))
                .toList();
        assertThat(byId).extracting(Order::getItemName)
                .as("id 升序 ⇒ item_name 升序;若回填错位此处必乱序")
                .containsExactly("R21医嘱-01", "R21医嘱-02", "R21医嘱-03",
                        "R21医嘱-04", "R21医嘱-05", "R21医嘱-06");

        // 断言 4(前半):去数据库直读,确认落库条数与内容一致,且 (id ↔ item_name) 与内存完全对应
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, item_name, visit_id FROM clinical.orders WHERE visit_id = ? ORDER BY id", visitId);
        assertThat(rows).hasSize(n);
        assertThat(rows).extracting(r -> ((Number) r.get("id")).longValue())
                .as("DB 落库 id 应与内存回填 id 完全一致")
                .containsExactlyElementsOf(byId.stream().map(Order::getId).toList());
        assertThat(rows).extracting(r -> (String) r.get("item_name"))
                .as("DB 落库 item_name 应与内存完全一致(证明回填 id 归属正确)")
                .containsExactlyElementsOf(byId.stream().map(Order::getItemName).toList());

        // 断言 3:每条 charge 的 orderId 都能在医嘱集合中找到,且 visitId / 内容一致
        Map<Long, Order> orderById = returned.stream().collect(toMap(Order::getId, identity()));
        List<Charge> charges = detail.getCharges();
        assertThat(charges).hasSize(n);
        for (Charge c : charges) {
            assertThat(c.getVisitId()).isEqualTo(visitId);
            Order o = orderById.get(c.getOrderId());
            assertThat(o).as("charge.orderId 必须命中一条本就诊医嘱").isNotNull();
            assertThat(c.getItemName()).isEqualTo(o.getItemName());
            assertThat(c.getAmount()).isEqualByComparingTo(o.getAmount());
        }
        // charge.order_id 集合与医嘱 id 集合一致(无遗漏、无幻觉 id)
        assertThat(charges.stream().map(Charge::getOrderId))
                .containsExactlyInAnyOrderElementsOf(orderById.keySet());

        // 断言 4(后半):DB 直读 charge 落库条数与归类一致
        Integer chargeRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM clinical.charge WHERE visit_id = ?", Integer.class, visitId);
        assertThat(chargeRows).isEqualTo(n);
        Integer misdirected = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM clinical.charge c WHERE c.visit_id = ? "
                        + "AND c.order_id NOT IN (SELECT id FROM clinical.orders WHERE visit_id = ?)",
                Integer.class, visitId, visitId);
        assertThat(misdirected).as("不存在指向本就诊之外医嘱的收费").isZero();

        // 金额汇总口径不变(11+24+39+56+75+96 = 301.00)
        assertThat(detail.getTotalAmount()).isEqualByComparingTo(new BigDecimal("301.00"));
    }

    @Test
    @DisplayName("R-21 createWithOrders: 空医嘱列表不触发批量插入(判空保护),不抛非法 SQL,无脏数据")
    void createWithOrders_emptyOrders_noInsert() {
        Visit visit = new Visit();
        visit.setPatientId(newTestPatientId());
        visit.setChiefComplaint("R21 空医嘱");

        VisitDetail detail = visitService.createWithOrders(visit, List.of());
        Long visitId = detail.getVisit().getId();

        assertThat(detail.getOrders()).isEmpty();
        assertThat(detail.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM clinical.orders WHERE visit_id = ?", Integer.class, visitId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM clinical.charge WHERE visit_id = ?", Integer.class, visitId)).isZero();
    }
}
