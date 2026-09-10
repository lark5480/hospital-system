package com.hospital.core.clinical.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitReadModel;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper.VisitAggregate;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.support.NameCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitReadModelService {

    private final VisitReadModelMapper readModelMapper;
    private final VisitMapper visitMapper;
    // R-16: orderMapper / chargeMapper 保留为构造参数以兼容既有 7 参构造(单测直接 new),
    // 但聚合已下沉到一条 SQL(selectAggregate),此处不再直接使用。
    @SuppressWarnings("unused")
    private final OrderMapper orderMapper;
    @SuppressWarnings("unused")
    private final ChargeMapper chargeMapper;
    private final PatientService patientService;
    private final StaffService staffService;
    private final DepartmentService departmentService;

    // R-16: 名称解析缓存(字段注入,不改构造签名,保持既有单测直接 new 的兼容性)。
    // 通过 NameCache 解析患者/医生/科室姓名,命中则零查询。
    @Autowired(required = false)
    private NameCache nameCache;

    /**
     * 刷新读模型（写操作后调用）。
     *
     * <p>R-16: 由原来的约 8 次 SQL 收敛为:
     * <ol>
     *   <li>查就诊 1 次;</li>
     *   <li>聚合值 1 次(一条标量子查询,取代原 orders + charge 两次全量查询);</li>
     *   <li>名称解析 0~3 次(走 {@link NameCache},命中即 0);</li>
     *   <li>查读模型 1 次 + 写入 1 次。</li>
     * </ol>
     *
     * @return 本次写回读模型的实体(供调用方复用已算好的名称/金额/缴费状态,避免再查一遍);
     *         就诊已被删除(物理删除)导致无法刷新时返回 {@code null}
     *
     * <p><b>为什么保持同步</b>:曾评估用 {@code @TransactionalEventListener(AFTER_COMMIT) + @Async}
     * 把刷新挪到事务提交后异步执行。但那会让读模型在写事务提交前不可见——
     * {@code VisitReadModelTest}(@SpringBootTest @Transactional)在 {@code createWithOrders} 之后
     * 同事务内立即读取读模型做断言,异步刷新(AFTER_COMMIT 在测试事务回滚时不触发)会导致断言失败;
     * 且当前调用方(如 buildDetailReusingReadModel)依赖 refresh 的返回值拼装详情,异步化后取不到。
     * 故本期保持同步;异步刷新需配合"调用方不再依赖返回值 + 测试改为等异步完成",风险大于收益,不做。
     */
    @Transactional
    public VisitReadModel refresh(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            deleteById(visitId);
            return null;
        }

        // R-16: 聚合值改用一条聚合 SQL(原为 selectList(orders) + selectList(charge) + 内存聚合)
        VisitAggregate agg = readModelMapper.selectAggregate(visitId);
        int orderCount = agg != null && agg.getOrderCount() != null ? agg.getOrderCount() : 0;
        int chargeCount = agg != null && agg.getChargeCount() != null ? agg.getChargeCount() : 0;
        int unpaidCount = agg != null && agg.getUnpaidCount() != null ? agg.getUnpaidCount() : 0;
        // R-06: 金额统一两位小数(HALF_UP),与 VisitService 侧口径一致
        BigDecimal totalAmount = agg != null && agg.getTotalAmount() != null
                ? agg.getTotalAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        // 缴费状态口径与原 resolvePayStatus 完全一致:无收费=NO_CHARGES,有未缴=HAS_UNPAID,否则 ALL_PAID
        String payStatus = chargeCount == 0 ? "NO_CHARGES" : (unpaidCount > 0 ? "HAS_UNPAID" : "ALL_PAID");

        // R-16: 名称解析走 NameCache,命中则零查询
        String patientName = loadName("patient", visit.getPatientId(), patientService::getName);
        String doctorName = loadName("staff", visit.getDoctorId(),
                id -> {
                    var staff = staffService.get(id);
                    return staff != null ? staff.getName() : null;
                });
        String deptName = loadName("dept", visit.getDeptId(),
                id -> {
                    var dept = departmentService.get(id);
                    return dept != null ? dept.getName() : null;
                });

        // 更新或插入读模型
        VisitReadModel rm = readModelMapper.selectByVisitId(visitId);
        if (rm == null) {
            rm = new VisitReadModel();
            rm.setVisitId(visitId);
        }

        rm.setPatientId(visit.getPatientId());
        rm.setDoctorId(visit.getDoctorId());
        rm.setDeptId(visit.getDeptId());
        rm.setChiefComplaint(visit.getChiefComplaint());
        rm.setStatus(visit.getStatus());
        rm.setVisitTime(visit.getVisitTime());
        rm.setCreatedAt(visit.getCreatedAt());
        rm.setPatientName(patientName);
        rm.setDoctorName(doctorName);
        rm.setDeptName(deptName);
        rm.setOrderCount(orderCount);
        rm.setTotalAmount(totalAmount);
        rm.setPayStatus(payStatus);
        rm.setUnpaidCount(unpaidCount);

        if (rm.getId() == null) {
            readModelMapper.insert(rm);
        } else {
            readModelMapper.updateById(rm);
        }

        log.debug("Refreshed read model for visit {}", visitId);
        return rm;
    }

    /**
     * 删除读模型
     */
    @Transactional
    public void deleteById(Long visitId) {
        readModelMapper.delete(
                new LambdaQueryWrapper<VisitReadModel>().eq(VisitReadModel::getVisitId, visitId));
    }

    /**
     * 初始化所有读模型（启动时调用）
     */
    @Transactional
    public void initAll() {
        List<Visit> visits = visitMapper.selectList(null);
        for (Visit visit : visits) {
            try {
                refresh(visit.getId());
            } catch (Exception e) {
                log.error("Failed to init read model for visit {}", visit.getId(), e);
            }
        }
        log.info("Initialized {} visit read models", visits.size());
    }

    /**
     * R-16: 解析单个名称 —— 优先走 {@link NameCache}(命中零查询),
     * 无 Spring 上下文(纯单测 nameCache 为 null)时退化为直接回源;异常一律吞掉返回 null。
     */
    private String loadName(String type, Long id, Function<Long, String> loader) {
        if (id == null) {
            return null;
        }
        try {
            if (nameCache != null) {
                return nameCache.getOrLoad(type, id, loader);
            }
            return loader.apply(id);
        } catch (Exception e) {
            return null;
        }
    }
}
