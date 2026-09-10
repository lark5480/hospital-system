package com.hospital.core.clinical.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitReadModel;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitReadModelService {

    private final VisitReadModelMapper readModelMapper;
    private final VisitMapper visitMapper;
    private final OrderMapper orderMapper;
    private final ChargeMapper chargeMapper;
    private final PatientService patientService;
    private final StaffService staffService;
    private final DepartmentService departmentService;

    /**
     * 刷新读模型（写操作后调用）
     */
    @Transactional
    public void refresh(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            deleteById(visitId);
            return;
        }

        // 查询关联数据
        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getVisitId, visitId));
        List<Charge> charges = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, visitId));

        // 计算聚合字段
        int orderCount = orders.size();
        // R-06: 汇总金额跳过 null 记录(脏数据/未回填时原实现会 NPE),并统一两位小数(HALF_UP),
        // 避免与 VisitService 侧金额口径不一致、以及多次刷新后 scale 漂移。
        BigDecimal totalAmount = sumAmount(charges);
        long unpaidCount = charges.stream()
                .filter(c -> "UNPAID".equals(c.getPayStatus()))
                .count();
        String payStatus = resolvePayStatus(charges);

        // 解析名称
        String patientName = resolveName(() -> patientService.getName(visit.getPatientId()));
        String doctorName = resolveName(() -> {
            var staff = staffService.get(visit.getDoctorId());
            return staff != null ? staff.getName() : null;
        });
        String deptName = resolveName(() -> {
            var dept = departmentService.get(visit.getDeptId());
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
        rm.setUnpaidCount((int) unpaidCount);

        if (rm.getId() == null) {
            readModelMapper.insert(rm);
        } else {
            readModelMapper.updateById(rm);
        }

        log.debug("Refreshed read model for visit {}", visitId);
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

    private String resolvePayStatus(List<Charge> charges) {
        if (charges.isEmpty()) return "NO_CHARGES";
        boolean hasUnpaid = charges.stream().anyMatch(c -> "UNPAID".equals(c.getPayStatus()));
        return hasUnpaid ? "HAS_UNPAID" : "ALL_PAID";
    }

    /**
     * R-06: 汇总收费金额 —— 跳过 null 金额(原实现 null 会 NPE),结果统一两位小数(HALF_UP)。
     */
    private BigDecimal sumAmount(List<Charge> charges) {
        if (charges == null || charges.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return charges.stream()
                .map(Charge::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @FunctionalInterface
    interface NameSupplier {
        String get() throws Exception;
    }

    private String resolveName(NameSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }
}
