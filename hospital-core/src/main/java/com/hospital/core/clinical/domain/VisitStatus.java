package com.hospital.core.clinical.domain;

import java.util.Set;

/**
 * 就诊单状态枚举 + 合法转换规则(状态收口)。
 * <p>
 * 转换图:CREATED → CONFIRMED → IN_PROGRESS → FINISHED(终态)。
 * 所有状态变更必须经 {@link Visit#transitTo(VisitStatus)},非法转换直接抛异常,
 * 杜绝各 Service 绕过守卫直接 setStatus 的旁路。
 */
public enum VisitStatus {

    CREATED,
    CONFIRMED,
    IN_PROGRESS,
    FINISHED;

    /** 当前状态允许转换到的目标状态集合。 */
    public Set<VisitStatus> allowedTransitions() {
        return switch (this) {
            case CREATED -> Set.of(CONFIRMED);
            case CONFIRMED -> Set.of(IN_PROGRESS, FINISHED);
            case IN_PROGRESS -> Set.of(FINISHED);
            case FINISHED -> Set.of();
        };
    }

    /** 终态不可再变更(追加医嘱/结算等操作的前置判断)。 */
    public boolean isTerminal() {
        return this == FINISHED;
    }

    /** 校验并执行状态转换;非法转换抛 IllegalStateException。 */
    public void assertTransitionTo(VisitStatus target) {
        if (!allowedTransitions().contains(target)) {
            throw new IllegalStateException(
                    "非法就诊状态转换: " + this + " → " + target);
        }
    }

    /** 数据库字符串 → 枚举(容错 null / 脏数据)。 */
    public static VisitStatus of(String value) {
        if (value == null) {
            return CREATED;
        }
        return valueOf(value);
    }
}
