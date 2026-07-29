package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.infrastructure.ChargeMapper;

@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {

    @Mock ChargeMapper chargeMapper;

    ChargeService service;

    @BeforeEach
    void setUp() {
        service = new ChargeService(chargeMapper);
    }

    private static Charge charge(Long id, Long visitId, Long orderId, BigDecimal amount, String payStatus) {
        Charge c = new Charge();
        c.setId(id);
        c.setVisitId(visitId);
        c.setOrderId(orderId);
        c.setAmount(amount);
        c.setPayStatus(payStatus);
        return c;
    }

    @Nested
    @DisplayName("断言全部已缴 assertAllPaid")
    class AssertAllPaid {

        @Test
        @DisplayName("全部已缴(PAID) → 不抛异常")
        void allPaid_noException() {
            when(chargeMapper.selectList(any())).thenReturn(List.of(
                    charge(1L, 10L, 1L, new BigDecimal("100.00"), "PAID"),
                    charge(2L, 10L, 2L, new BigDecimal("200.00"), "PAID")
            ));

            service.assertAllPaid(10L);
            // no exception → pass
        }

        @Test
        @DisplayName("无收费记录 → 不抛异常(视为已缴清)")
        void noCharges_noException() {
            when(chargeMapper.selectList(any())).thenReturn(List.of());

            service.assertAllPaid(10L);
            // no exception → pass
        }

        @Test
        @DisplayName("有未缴(UNPAID)费用 → IllegalStateException")
        void hasUnpaid_throws() {
            when(chargeMapper.selectList(any())).thenReturn(List.of(
                    charge(1L, 10L, 1L, new BigDecimal("100.00"), "PAID"),
                    charge(2L, 10L, 2L, new BigDecimal("200.00"), "UNPAID")
            ));

            assertThatThrownBy(() -> service.assertAllPaid(10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("尚有未缴费用");
        }

        @Test
        @DisplayName("退费(REFUNDED)记录不视为未缴 → 不抛异常")
        void refunded_notUnpaid() {
            when(chargeMapper.selectList(any())).thenReturn(List.of(
                    charge(1L, 10L, 1L, new BigDecimal("100.00"), "PAID"),
                    charge(2L, 10L, 2L, new BigDecimal("50.00"), "REFUNDED")
            ));

            service.assertAllPaid(10L);
            // REFUNDED != UNPAID, so no exception
        }
    }
}
