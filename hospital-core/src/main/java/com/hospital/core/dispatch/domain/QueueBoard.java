package com.hospital.core.dispatch.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 排队看板读模型(CQRS 投影)。
 * 与写模型 dispatch.exam_task 1:1 映射(id 相同),但只保留看板展示所需的
 * 反规范化列,且是"只读侧"的优化形态。当前为同库物化投影;
 * 抽出 Dispatch 服务时,此表将迁移为独立读存储(如只读副本 / Elastic),由事件投影更新。
 */
@Data
@TableName("dispatch.queue_board")
public class QueueBoard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String station;
    private String patientName;
    private String itemName;

    /** PENDING / IN_PROGRESS / DONE / SKIPPED */
    private String status;

    private Integer seq;

    private LocalDateTime startedAt;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
}
