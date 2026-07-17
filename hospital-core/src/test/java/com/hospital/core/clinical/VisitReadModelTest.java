package com.hospital.core.clinical;

import com.hospital.core.clinical.application.PageResult;
import com.hospital.core.clinical.application.VisitDetail;
import com.hospital.core.clinical.application.VisitReadModelService;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class VisitReadModelTest {

    @Autowired
    private VisitService visitService;
    
    @Autowired
    private VisitReadModelService readModelService;
    
    @Autowired
    private VisitReadModelMapper readModelMapper;

    @Test
    void shouldRefreshReadModelAfterCreateVisit() {
        // Given
        Visit visit = new Visit();
        visit.setPatientId(1L);
        visit.setDoctorId(1L);
        visit.setDeptId(1L);
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
        // Given - 创建测试数据
        Visit visit = new Visit();
        visit.setPatientId(1L);
        visit.setDoctorId(1L);
        visit.setDeptId(1L);
        visit.setChiefComplaint("头痛");
        visitService.createWithOrders(visit, List.of());
        
        // When
        PageResult<VisitDetail> result = visitService.listPage("头痛", 1, 10, null);
        
        // Then
        assertThat(result.getItems()).isNotEmpty();
        assertThat(result.getTotal()).isEqualTo(1);
    }
}
