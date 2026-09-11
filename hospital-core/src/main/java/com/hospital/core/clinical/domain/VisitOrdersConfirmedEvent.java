package com.hospital.core.clinical.domain;

import java.util.List;

/**
 * 就诊单"医嘱已锁定、需要生成下游单据"事件(自包含快照)—— R-64。
 *
 * <p><b>为什么要有这个事件</b>:领域不变式是「就诊单已确单 ⇒ 每条 LAB/MEDICATION 医嘱都被
 * 对应的下游单据(检验申请 / 处方)明细覆盖」。该不变式原先由浏览器维持 —— 前端在确单成功后
 * <b>再发两个 HTTP</b>({@code POST /api/lab/requisitions}、{@code POST /api/pharmacy/prescriptions}),
 * 属"没有补偿的客户端编排",有三种失败面目且都无感知:
 * <ol>
 *   <li>请求根本没发出去(前端据本地快照 {@code store.detail.orders} 判断,快照 stale 就静默跳过);</li>
 *   <li>请求被 Bean Validation 拦在切面之前 → <b>连审计都不留痕</b>
 *       (实测:库里有 {@code CONFIRM_VISIT} + {@code CREATE_PRESCRIPTION},唯独没有 {@code CREATE_REQUISITION});</li>
 *   <li>第 2 个请求内部异常 → 整个事务回滚,单据永久缺失(实测:
 *       {@code CREATE_REQUISITION FAILED: 非法就诊状态转换: IN_PROGRESS → CONFIRMED})。</li>
 * </ol>
 * 因此把责任收回服务端:由 {@code clinical} 发布本事件,下游模块各自订阅生成。
 *
 * <p><b>两个触发点,同一个语义</b>(都由 {@code VisitService} 发布):
 * <ul>
 *   <li>{@code confirm()} —— 确单时,把当时所有 {@code status=CREATED} 的医嘱按类型<b>批量</b>放入桶;</li>
 *   <li>{@code addOrder()} —— 就诊已确单(CONFIRMED/IN_PROGRESS)时追加医嘱,该医嘱<b>即刻</b>锁定,故单条入桶。</li>
 * </ul>
 * 二者对下游的含义完全一致:"这些医嘱需要有对应的下游单据"。缺了后者就会出现
 * "确单后再追加检验医嘱 → 检验科永远看不到"(实测中的真实场景)。
 *
 * <p><b>为什么是自包含快照</b>:事件只携带医嘱 id 分桶,监听器据"桶是否为空"决定要不要生成,
 * 无需回查临床模块的实体 —— 与 {@code booking → dispatch} 的既有范式
 * (见 {@code DispatchService#onAppointmentCreated})保持一致。
 *
 * <p><b>为什么监听器在 AFTER_COMMIT 执行</b>:生成必须读得到已提交的医嘱与就诊状态。
 * 代价是"生成失败不回滚主流程" —— 这是<b>刻意</b>的取舍:确单/下医嘱是临床主流程,
 * 不能被下游单据拖垮;失败由 {@code DownstreamDocReconcileJob} 对账补建兜底(最终一致)。
 *
 * @param visitId              就诊单 ID
 * @param patientId            患者 ID(便于下游免回查)
 * @param doctorId             就诊医生 ID(确单时若为空会回填当前操作医生,故取回调后的值)
 * @param labOrderIds          需要生成检验申请的医嘱 ID;为空表示本次无需生成
 * @param medicationOrderIds   需要生成处方的医嘱 ID;为空表示本次无需生成
 * @param trigger              触发点;审计与日志据此区分"确单时批量生成"与"确单后追加单条"
 */
public record VisitOrdersConfirmedEvent(
        Long visitId,
        Long patientId,
        Long doctorId,
        List<Long> labOrderIds,
        List<Long> medicationOrderIds,
        Trigger trigger
) {

    /**
     * 触发点。
     *
     * <p>为什么要显式带在事件里:下游生成不再经过 Controller,审计轨迹由监听器显式补写
     * (见 {@code AuditRecorder}),而"同一张就诊单为什么会有两张申请"这类问题
     * 只能靠明细里的触发原因回答。
     */
    public enum Trigger {
        /** 确单时批量:把当时全部 {@code status=CREATED} 的医嘱一次性交给下游。 */
        CONFIRM("确单"),
        /** 确单后追加:就诊已锁定,新医嘱即刻需要下游单据。 */
        APPEND_ORDER("确单后追加医嘱");

        private final String label;

        Trigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 两个桶都为空则没有任何下游动作可做,发布方据此跳过(避免空事件污染监听器日志)。 */
    public boolean isEmpty() {
        return (labOrderIds == null || labOrderIds.isEmpty())
                && (medicationOrderIds == null || medicationOrderIds.isEmpty());
    }

    /** 供审计/日志使用的触发点描述;trigger 缺失时降级为"未知"而非抛异常(审计不该因此失败)。 */
    public String triggerLabel() {
        return trigger == null ? "未知" : trigger.label();
    }
}
