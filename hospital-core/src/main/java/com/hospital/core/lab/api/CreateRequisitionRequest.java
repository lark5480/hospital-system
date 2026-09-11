package com.hospital.core.lab.api;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建检验申请请求。orderIds 为空时自动拾取该就诊下全部 LAB+CREATED 医嘱。
 *
 * <p><b>orderIds 必须保持"允许为空、不加校验"</b>:为空或不传时,由
 * {@code LabService#createFromVisit} 自动拾取该就诊下全部 {@code LAB} + {@code CREATED} 医嘱。
 *
 * <p>历史缺陷留痕:该字段曾被加上 {@code @NotEmpty},与本契约直接矛盾 —— 前端
 * ({@code VisitDetailView} 的"确单自动生成检验申请"与"追加 LAB 医嘱后同步"两条路径)
 * 从不传 orderIds,于是请求在校验层就被 400 拒掉,服务层的自动拾取逻辑永远走不到;
 * 而前端只把非 409 的错误吞成 warning toast,长期无人发现。请勿"顺手加校验"。
 *
 * <p>visitId / doctorId 仍为必填:前端这两项始终会传。
 */
@Data
public class CreateRequisitionRequest {
    @NotNull(message = "就诊ID不能为空")
    private Long visitId;
    @NotNull(message = "医生ID不能为空")
    private Long doctorId;

    /** 可为空:为空时自动拾取该就诊下全部 LAB+CREATED 医嘱(见类注释)。 */
    private List<Long> orderIds;
}
