package com.hospital.core.lab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 「创建检验申请」请求的校验契约测试(纯 Bean Validation,不启 Spring)。
 *
 * <p>锁住一条曾被破坏、且破坏后极难发现的契约:{@code orderIds} <b>必须允许为空/不传</b>。
 *
 * <p>背景:该字段曾被加上 {@code @NotEmpty},而 {@code LabService#createFromVisit} 的实现是
 * "为空时自动拾取该就诊下全部 LAB+CREATED 医嘱"。两者矛盾导致前端两条调用路径
 * ({@code VisitDetailView} 的确单自动生成、追加 LAB 医嘱后同步 —— 二者都只传
 * {@code visitId} + {@code doctorId})在校验层就被 400 拒掉,服务层的自动拾取逻辑<b>永远走不到</b>;
 * 而前端只把非 409 的错误吞成 warning toast,所以长期无人察觉。
 *
 * <p>本测试之所以放在校验层而不是接口层:上面这个缺陷的本质是"注解与契约不符",
 * 用 {@link Validator} 直接断言"哪些入参形态不得产生校验错误"最贴近根因,成本也最低。
 */
class CreateRequisitionRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        // 不 close:ValidatorFactory 关闭后其 Validator 不可再用,而这里要被所有用例复用
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static CreateRequisitionRequest req(Long visitId, Long doctorId, List<Long> orderIds) {
        CreateRequisitionRequest r = new CreateRequisitionRequest();
        r.setVisitId(visitId);
        r.setDoctorId(doctorId);
        r.setOrderIds(orderIds);
        return r;
    }

    @Test
    @DisplayName("不传 orderIds(前端两条调用路径的真实形态)→ 不得产生任何校验错误")
    void orderIdsAbsent_mustPassValidation() {
        assertThat(validator.validate(req(1L, 1L, null)))
                .as("orderIds 缺省是正常用法:服务层会自动拾取该就诊下全部 LAB+CREATED 医嘱")
                .isEmpty();
    }

    @Test
    @DisplayName("orderIds 为空数组 → 不得产生任何校验错误")
    void orderIdsEmpty_mustPassValidation() {
        assertThat(validator.validate(req(1L, 1L, List.of())))
                .as("服务层按 isEmpty 判定是否要自动拾取,空数组与 null 语义相同,都必须放行")
                .isEmpty();
    }

    @Test
    @DisplayName("显式指定 orderIds → 正常通过")
    void orderIdsProvided_mustPassValidation() {
        assertThat(validator.validate(req(1L, 1L, List.of(1L, 2L)))).isEmpty();
    }

    @Test
    @DisplayName("visitId / doctorId 缺失仍必须报错(去掉 orderIds 校验不得顺带放宽其它字段)")
    void visitIdAndDoctorIdAreStillRequired() {
        Set<ConstraintViolation<CreateRequisitionRequest>> violations = validator.validate(req(null, null, null));

        assertThat(violations)
                .as("这两项前端始终会传,保留必填;此断言防止将来把校验整体删掉")
                .hasSize(2);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("visitId", "doctorId");
    }
}
