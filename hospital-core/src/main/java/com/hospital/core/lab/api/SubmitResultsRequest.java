package com.hospital.core.lab.api;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 录入检验结果请求。 */
@Data
public class SubmitResultsRequest {
    @NotNull(message = "检验员ID不能为空")
    private Long technicianId;
    @NotEmpty(message = "检验结果不能为空")
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
