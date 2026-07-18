package com.hospital.core.dispatch.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 体检任务(写模型 / 聚合)。一个预约单 → 套餐内每个项目生成一条 ExamTask。
 * 落在某个 station(工位),按 seq 排成该 station 的队列。
 * 状态机:PENDING(待检) → IN_PROGRESS(检查中) → DONE(完成);SKIPPED 跳过。
 * 注:patientName / itemName 在生成时反规范化写入,任务生命周期内不变,读模型直接复用。
 */
@Data
@TableName("dispatch.exam_task")
public class ExamTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appointmentId;
    private Long patientId;
    private Long packageId;

    /** 工位/科室,如 采血室 / B超室 */
    private String station;

    private String itemName;
    private String patientName;

    /** PENDING / IN_PROGRESS / DONE / SKIPPED */
    private String status;

    /** 同 station 队列内的顺序 */
    private Integer seq;

    private LocalDateTime startedAt;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
}
