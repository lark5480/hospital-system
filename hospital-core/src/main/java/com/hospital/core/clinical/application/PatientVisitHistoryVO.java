package com.hospital.core.clinical.application;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 患者历史就诊记录 VO(含医嘱),供新建就诊时医生参考。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PatientVisitHistoryVO {
    private Long visitId;
    private String visitTime;
    private String status;
    private String chiefComplaint;
    private String doctorName;
    private String deptName;
    private List<OrderSummary> orders;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OrderSummary {
        private Long orderId;
        private String type;
        private String itemName;
        private String status;
        /** 检查所见(EXAM 类医嘱) */
        private String finding;
    }
}
