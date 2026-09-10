package com.hospital.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

/**
 * R-03 回归测试:未注入 {@code FILE_INTERNAL_TOKEN} 时网关必须能正常启动。
 *
 * <p>踩过的坑:{@code hospital-file} 路由上有
 * {@code AddRequestHeader=X-Internal-Token, ${file.internal-token}},
 * 绑定的 {@code NameValueConfig} 要求 value 非空。当 {@code file.internal-token}
 * 的默认值写成空串({@code ${FILE_INTERNAL_TOKEN:}})时,占位符解析结果为空,
 * 网关在启动阶段就失败:
 * <pre>
 * Binding to target AbstractNameValueGatewayFilterFactory$NameValueConfig failed:
 *     Property: .value  Value: "null"  Reason: 不能为空
 * </pre>
 *
 * <p>{@link GatewayRouteGuardTest} 用 {@code properties = "file.internal-token=gateway-test-token"}
 * 显式注入了非空值,正好绕过了这个场景,所以必须单独用"不覆盖该配置"的上下文守住。
 */
@SpringBootTest
class GatewayDefaultTokenContextTest {

    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    @DisplayName("R-03 回归:未配置 FILE_INTERNAL_TOKEN 时,AddRequestHeader 的 value 不能为空(否则网关启动失败)")
    void internalTokenHeaderValueShouldNeverBeEmpty() {
        List<RouteDefinition> definitions = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block(ROUTE_TIMEOUT);

        assertThat(definitions).as("路由定义列表").isNotNull();

        RouteDefinition fileRoute = definitions.stream()
                .filter(d -> "hospital-file".equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("application.yml 中找不到路由: hospital-file"));

        List<String> values = fileRoute.getFilters().stream()
                .filter(f -> "AddRequestHeader".equals(f.getName()))
                .flatMap(f -> f.getArgs().values().stream())
                .toList();

        assertThat(values).as("AddRequestHeader 的参数列表").isNotEmpty();
        assertThat(values).as("AddRequestHeader 的参数不得为 null").doesNotContainNull();
        assertThat(values).as("AddRequestHeader 的值不得为空串(NameValueConfig 不允许,会导致网关启动失败)")
                .noneMatch(String::isBlank);
        assertThat(values).as("AddRequestHeader 必须声明 X-Internal-Token 头")
                .anyMatch(v -> v.contains("X-Internal-Token"));
    }
}
