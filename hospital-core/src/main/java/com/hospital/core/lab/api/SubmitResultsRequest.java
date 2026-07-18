package com.hospital.core.lab.api;

import lombok.Data;
import java.util.List;

/** 录入检验结果请求。 */
@Data
public class SubmitResultsRequest {
    private Long technicianId;
    private List<ResultItemEntry> items;

    @Data
    public static class ResultItemEntry {
        private Long itemId;
        private String resultValue;
        private String unit;
        private String refRange;
        private String abnormalFlag;
    }
}
