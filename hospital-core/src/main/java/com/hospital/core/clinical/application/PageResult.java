package com.hospital.core.clinical.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 分页结果通用包装。 */
@Data
@Builder
@AllArgsConstructor
public class PageResult<T> {
    private List<T> items;
    private int total;
    private int pageNum;
    private int pageSize;
}