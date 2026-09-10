package com.hospital.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * R-27:网关路由配置守护测试(不启动网络端口,直接断言 RouteLocator / RouteDefinition)。
 *
 * <p>守护两件会被"无声回退"的事:
 * <ol>
 *   <li><b>R-03 关键防线</b>:{@code hospital-file} 路由上的
 *       {@code AddRequestHeader=X-Internal-Token, ${file.internal-token}}。
 *       它由网关代发内部令牌,浏览器侧永远不接触令牌;
 *       一旦被删或改错,file-service 的 InternalTokenFilter 会把所有经网关的请求 401,
 *       或者(更糟)有人为了"修 401"把 file-service 的令牌校验一起删掉 —— 那就是文件服务对外裸奔。</li>
 *   <li><b>路由表完整性</b>:/fhir/** 必须继续指向 core(走 core 自己的鉴权链),
 *       各业务路由不能被误删/误改端口。</li>
 * </ol>
 *
 * <p>这里用 {@code RouteLocator} + {@code RouteDefinitionLocator} 断言,
 * 不启动 Netty、不发真实请求,避免 WebFlux 测试的复杂度与外部依赖。
 */
@SpringBootTest(properties = "file.internal-token=gateway-test-token")
class GatewayRouteGuardTest {

    // R-27: 路由组装是同步的,这里只给一个宽松上限防止测试挂死
    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    @DisplayName("R-27 用例A:RouteLocator 中存在预期的路由 id 集合(防止路由被误删)")
    void routeIdsShouldContainExpectedRoutes() {
        Set<String> ids = routeLocator.getRoutes()
                .map(route -> route.getId())
                .collectList()
                .block(ROUTE_TIMEOUT)
                .stream()
                .collect(java.util.stream.Collectors.toSet());

        assertThat(ids).contains(
                "hospital-auth",
                "hospital-core",
                "hospital-notification",
                "hospital-file",
                "hospital-patient",
                "hospital-pharmacy",
                "hospital-lab",
                "hospital-exam",
                "hospital-report",
                "hospital-fhir",
                "core-swagger-ui");
        // 至少包含 hospital-file / hospital-core / hospital-fhir 三条关键路由
        assertThat(ids).contains("hospital-file", "hospital-core", "hospital-fhir");
        assertThat(ids).hasSizeGreaterThanOrEqualTo(11);
    }

    @Test
    @DisplayName("R-27 用例B:hospital-file 路由必须带 AddRequestHeader=X-Internal-Token(R-03 不回退)")
    void fileRouteShouldAddInternalTokenHeader() {
        RouteDefinition fileRoute = routeDefinition("hospital-file");

        List<String> filterNames = fileRoute.getFilters().stream()
                .map(f -> f.getName())
                .toList();
        assertThat(filterNames).as("hospital-file 路由的过滤器列表").contains("AddRequestHeader");

        // 断言请求头名确实是 X-Internal-Token
        boolean headerNamePresent = fileRoute.getFilters().stream()
                .filter(f -> "AddRequestHeader".equals(f.getName()))
                .anyMatch(f -> f.getArgs().values().stream()
                        .anyMatch(v -> v != null && v.contains("X-Internal-Token")));
        assertThat(headerNamePresent)
                .as("AddRequestHeader 必须是 X-Internal-Token(与 file-service 的 InternalTokenFilter 对齐)")
                .isTrue();

        // 断言令牌值确实取自 file.internal-token 配置(而不是被写成硬编码/被清空)
        boolean tokenValueWired = fileRoute.getFilters().stream()
                .filter(f -> "AddRequestHeader".equals(f.getName()))
                .anyMatch(f -> f.getArgs().values().stream()
                        .anyMatch(v -> "gateway-test-token".equals(v)));
        assertThat(tokenValueWired)
                .as("AddRequestHeader 的值必须来源于 ${file.internal-token}")
                .isTrue();
    }

    @Test
    @DisplayName("R-27 用例C:hospital-file 指向 8103,hospital-fhir 指向 core(8101)")
    void routeUrisShouldPointToExpectedDownstream() {
        var routes = routeLocator.getRoutes().collectList().block(ROUTE_TIMEOUT);

        assertThat(routes).isNotNull();
        String fileUri = routes.stream()
                .filter(r -> "hospital-file".equals(r.getId()))
                .findFirst()
                .orElseThrow()
                .getUri()
                .toString();
        String fhirUri = routes.stream()
                .filter(r -> "hospital-fhir".equals(r.getId()))
                .findFirst()
                .orElseThrow()
                .getUri()
                .toString();

        assertThat(fileUri).as("hospital-file 必须指向文件服务 8103").endsWith(":8103");
        assertThat(fhirUri).as("/fhir/** 必须继续走 core 8101 的鉴权链").endsWith(":8101");
    }

    /**
     * 按 id 取路由定义。
     *
     * @param id 路由 id
     * @return 对应的 RouteDefinition;不存在则抛 AssertionError(让用例给出可读失败信息)
     */
    private RouteDefinition routeDefinition(String id) {
        List<RouteDefinition> definitions = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block(ROUTE_TIMEOUT);

        assertThat(definitions).as("路由定义列表").isNotNull();
        return definitions.stream()
                .filter(d -> id.equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("application.yml 中找不到路由: " + id));
    }
}
