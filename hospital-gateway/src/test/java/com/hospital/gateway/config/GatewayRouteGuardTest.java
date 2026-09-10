package com.hospital.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * 网关路由配置守护测试(不启动网络端口,直接断言 RouteLocator / RouteDefinition)。
 *
 * <p>守护两类会被"无声回退"的事:
 * <ol>
 *   <li><b>R-62 关键防线</b>:不得存在任何把 {@code /api/files/**} 转发到 file-service 的路由。
 *       原先这里有一条 {@code hospital-file} 路由 + {@code AddRequestHeader=X-Internal-Token},
 *       但网关默认 profile 就是 {@code permitAll}(且仓库中不存在 {@code application-iam.yml}),
 *       而 {@code AddRequestHeader} 对匿名请求同样生效 —— 该令牌对"经网关的匿名调用者"毫无约束。
 *       更糟的是 file-service 的 {@code list} 在不传 patientId 时返回全部对象及其 patientId,
 *       正好是 {@code download} 归属校验所需要的那组对应关系,于是形成
 *       "匿名列举 → 带着 patientId 下载" 的闭环。
 *       因此本测试<b>反向断言</b>:一旦有人为了"方便"把这条路由加回来,构建立即失败。</li>
 *   <li><b>路由表完整性</b>:/fhir/** 必须继续指向 core(走 core 自己的鉴权链),
 *       各业务路由不能被误删/误改端口。</li>
 * </ol>
 *
 * <p>这里用 {@code RouteLocator} + {@code RouteDefinitionLocator} 断言,
 * 不启动 Netty、不发真实请求,避免 WebFlux 测试的复杂度与外部依赖。
 */
@SpringBootTest
class GatewayRouteGuardTest {

    // R-27: 路由组装是同步的,这里只给一个宽松上限防止测试挂死
    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(10);

    /** R-62: file-service 的端口。网关侧不应再有任何路由指向它。 */
    private static final String FILE_SERVICE_PORT_SUFFIX = ":8103";

    /** R-62: 已撤除的旁路路由 id。 */
    private static final String REMOVED_FILE_ROUTE_ID = "hospital-file";

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
                "hospital-patient",
                "hospital-pharmacy",
                "hospital-lab",
                "hospital-exam",
                "hospital-report",
                "hospital-fhir",
                "core-swagger-ui");
        assertThat(ids).contains("hospital-core", "hospital-fhir");
        assertThat(ids).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("R-62 用例B:不存在任何暴露 /api/files/** 的路由(hospital-file 不得被加回)")
    void fileServiceMustNotBeExposedThroughGateway() {
        List<RouteDefinition> definitions = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block(ROUTE_TIMEOUT);

        assertThat(definitions).as("路由定义列表").isNotNull();

        // 扫描所有路由的所有谓词参数,任何一条含 /api/files 都算回归
        List<String> predicateArgs = definitions.stream()
                .flatMap(d -> d.getPredicates().stream())
                .flatMap(p -> p.getArgs().values().stream())
                .filter(Objects::nonNull)
                .toList();

        assertThat(predicateArgs)
                .as("网关不得再暴露 /api/files/** —— 网关层没有身份上下文,"
                        + "file-service 的 list 会把 download 归属校验所需的 patientId 一并送出去(R-62);"
                        + "文件访问必须经 core 的 /api/core/files")
                .noneMatch(v -> v.contains("/api/files"));

        assertThat(definitions.stream().map(RouteDefinition::getId).toList())
                .as("已撤除的旁路路由 id 不得复活")
                .doesNotContain(REMOVED_FILE_ROUTE_ID);
    }

    @Test
    @DisplayName("R-62 用例C:没有任何路由指向 file-service(8103);/fhir/** 仍走 core(8101)")
    void noRouteShouldTargetFileService() {
        var routes = routeLocator.getRoutes().collectList().block(ROUTE_TIMEOUT);

        assertThat(routes).isNotNull();

        assertThat(routes.stream().map(r -> r.getUri().toString()).toList())
                .as("file-service 必须只在内网可达,网关不应再有任何直连路由")
                .noneMatch(uri -> uri.endsWith(FILE_SERVICE_PORT_SUFFIX));

        String fhirUri = routes.stream()
                .filter(r -> "hospital-fhir".equals(r.getId()))
                .findFirst()
                .orElseThrow()
                .getUri()
                .toString();
        assertThat(fhirUri).as("/fhir/** 必须继续走 core 8101 的鉴权链").endsWith(":8101");
    }

    @Test
    @DisplayName("R-62 用例D:/api/core/files 被 hospital-core 路由覆盖(代理入口可达)")
    void fileProxyPathShouldBeCoveredByCoreRoute() {
        RouteDefinition coreRoute = routeDefinition("hospital-core");

        boolean coversCorePrefix = coreRoute.getPredicates().stream()
                .flatMap(p -> p.getArgs().values().stream())
                .filter(Objects::nonNull)
                .anyMatch(v -> v.contains("/api/core/**"));

        assertThat(coversCorePrefix)
                .as("/api/core/** 必须由 hospital-core 路由承接,否则 FileProxyController 不可达")
                .isTrue();
        assertThat(coreRoute.getUri().toString()).endsWith(":8101");
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
