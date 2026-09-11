package com.hospital.core.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * R-59: {@link VisitStatus#of(String)} 转换矩阵固化测试(纯 JUnit,无 Mockito / 无 Spring 上下文)。
 *
 * <p>目的:把"数据库字符串 → 枚举"这一处最容易藏脏数据的入口的<b>当前实际行为</b>用测试钉死,
 * 防止后续无声变更。经 read_file 核实,真实实现为:
 * <ul>
 *   <li>{@code of(null)} → 直接返回 {@code CREATED}(静默降级);</li>
 *   <li>其余非 null 值一律走 {@code Enum.valueOf(value)}(<b>大小写敏感</b>),
 *       非法/空串/纯空白/大小写不符都会抛 {@code IllegalArgumentException}。</li>
 * </ul>
 *
 * <p>注意:本测试只固化现状,<b>不改 {@link VisitStatus} 实现</b> —— 因为 {@code of()} 被状态机
 * (确单/结算/结束等)大量依赖,贸然改成"未知值一律抛异常"会改变运行期语义。
 */
class VisitStatusTest {

    @ParameterizedTest(name = "of({0}) 往返 → {0}")
    @CsvSource({
            "CREATED",
            "CONFIRMED",
            "IN_PROGRESS",
            "FINISHED"
    })
    @DisplayName("R-59 全部合法状态值:of() 往返转换为同一枚举常量")
    void of_roundTripForAllLegalValues(String value) {
        VisitStatus status = VisitStatus.of(value);
        assertThat(status).isSameAs(VisitStatus.valueOf(value));
        assertThat(status.name()).isEqualTo(value);
    }

    @ParameterizedTest(name = "of({0}) → 抛 IllegalArgumentException")
    @ValueSource(strings = {"created", "Confirmed", "in_progress", "finished", "Finished"})
    @DisplayName("R-59 大小写不敏感?实测为大小写敏感:非全大写写法一律抛异常")
    void of_isCaseSensitive(String value) {
        // 实现走 Enum.valueOf(大小写敏感),故 "created" 等小写/混写都不会被接受。
        assertThatThrownBy(() -> VisitStatus.of(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R-59 实测:of(null) 静默降级为 CREATED")
    void of_null_defaultsToCreated() {
        // 静默降级为 CREATED 会掩盖脏数据:DB 中 status 为 NULL 的就诊单会被当作"草稿"处理,
        // 而不是显式报错暴露数据问题 —— 见 R-59。本期仅固化现状,不改实现。
        assertThat(VisitStatus.of(null)).isSameAs(VisitStatus.CREATED);
    }

    @ParameterizedTest(name = "of(\"{0}\") → 抛 IllegalArgumentException")
    @ValueSource(strings = {"", "  ", "NOT_A_STATUS", "CREATED "})
    @DisplayName("R-59 实测:空串/纯空白/未知值走 Enum.valueOf 直接抛异常(并非静默降级)")
    void of_invalid_throws(String value) {
        // 固化现状:空串 / 纯空白 / 未知值都不会被静默降级为 CREATED,而是抛 IllegalArgumentException。
        // 与 of(null) 的静默降级行为<b>不一致</b>,后续应统一(要么都抛、要么都降级并告警)—— 见 R-59。
        assertThatThrownBy(() -> VisitStatus.of(value))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
