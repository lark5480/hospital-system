package com.hospital.core.clinical.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.infrastructure.ChargeMapper;

import lombok.RequiredArgsConstructor;

/**
 * 收费应用服务:收费前置断言。
 * 检查/检验执行前必须调用 assertAllPaid,未缴费则拦截。
 */
@Service
@RequiredArgsConstructor
public class ChargeService {

    private final ChargeMapper chargeMapper;

    /**
     * 断言该就诊所有费用已缴清。
     * @throws IllegalStateException 有未缴费用
     */
    @Transactional(readOnly = true)
    public void assertAllPaid(Long visitId) {
        List<Charge> charges = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, visitId));
        boolean hasUnpaid = charges.stream()
                .anyMatch(c -> "UNPAID".equals(c.getPayStatus()));
        if (hasUnpaid) {
            throw new IllegalStateException("该就诊尚有未缴费用,请先结算后再执行");
        }
    }
}
