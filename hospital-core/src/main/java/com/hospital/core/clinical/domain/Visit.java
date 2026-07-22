package com.hospital.core.clinical.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门诊就诊聚合根(示例)。
 * 真实系统里一次就诊会关联医嘱/收费/病历,这里只保留最小骨架用于演示模块边界与事务。
 */
@Data
@TableName("clinical.visit")
public class Visit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long doctorId;
    private Long deptId;
    private String chiefComplaint;

    /** 就诊状态,合法转换见 {@link VisitStatus};变更须走 {@link #transitTo(VisitStatus)}。 */
    private String status;

    private LocalDateTime visitTime;
    private LocalDateTime createdAt;

    /** 状态收口:校验合法转换后更新 status,非法转换抛 IllegalStateException。 */
    public void transitTo(VisitStatus target) {
        VisitStatus.of(this.status).assertTransitionTo(target);
        this.status = target.name();
    }
}
