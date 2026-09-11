package com.hospital.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.Environment;

/**
 * R-62 回归测试:网关上下文中不得再出现 file-service 的内部调用令牌。
 *
 * <p>背景 —— 该测试的前身是 R-03 的启动回归:原先 {@code hospital-file} 路由上挂着
 * {@code AddRequestHeader=X-Internal-Token, ${file.internal-token}},
 * 而它绑定的 {@code NameValueConfig} 要求 value 非空,默认值写成空串时网关会在启动阶段
 * 直接失败({@code Property: .value Reason: 不能为空})。
 *
 * <p>R-62 之后这条路由被整体撤除,令牌改为只在 core({@code FILE_INTERNAL_TOKEN})
 * 与 file-service 之间传递。于是这里要守的契约<b>正好相反</b>:
 * <ul>
 *   <li>{@code file.internal-token} 不得再出现在网关的属性源里 —— 说明令牌没有回流到网关;</li>
 *   <li>没有任何路由带 {@code AddRequestHeader: X-Internal-Token} —— 说明不存在"由网关代发令牌"
 *       的路径。网关默认 profile 就是 {@code permitAll}(仓库中也没有 {@code application-iam.yml}),
 *       它代发的令牌对匿名调用者同样生效,<b>不构成安全边界</b>。</li>
 * </ul>
 *
 * <p>与 {@link GatewayRouteGuardTest} 的分工:那边断言"路由不存在",
 * 这边断言"配置属性不存在"。只守一边,令牌仍可能悄悄回流。
 */
@SpringBootTest
class GatewayInternalTokenAbsenceTest {

    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("R-62 回归:网关不再持有 file.internal-token 配置")
    void gatewayMustNotHoldInternalTokenProperty() {
        assertThat(environment.containsProperty("file.internal-token"))
                .as("网关不应再持有 file.internal-token —— 令牌只在 core ↔ file-service 之间(R-62)")
                .isFalse();
    }

    @Test
    @DisplayName("R-62 回归:没有任何路由代发 X-Internal-Token 头")
    void noRouteShouldInjectInternalTokenHeader() {
        List<RouteDefinition> definitions = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block(ROUTE_TIMEOUT);

        assertThat(definitions).as("路由定义列表").isNotNull();

        List<String> injectedHeaderArgs = definitions.stream()
                .flatMap(d -> d.getFilters().stream())
                .filter(f -> "AddRequestHeader".equals(f.getName()))
                .flatMap(f -> f.getArgs().values().stream())
                .filter(Objects::nonNull)
                .toList();

        assertThat(injectedHeaderArgs)
                .as("不得有任何路由代发 X-Internal-Token:网关没有身份上下文,"
                        + "它注入的令牌对匿名请求同样生效(R-62)")
                .noneMatch(v -> v.contains("X-Internal-Token"));
    }
}
