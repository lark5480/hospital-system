package com.hospital.core.booking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 体检项目(套餐内的检查项,对应体检中心的某个 station 工位)。 */
@Data
@TableName("booking.exam_item")
public class ExamItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long packageId;

    private String name;

    /** 所属工位/科室,如 采血室 / B超室 / 心电图室 */
    private String station;

    /** 队列顺序(同套餐内项目之间的先后,用于生成 station 任务序列) */
    private Integer orderNo;

    /** 预计耗时(分钟) */
    private Integer durationMin;
}
