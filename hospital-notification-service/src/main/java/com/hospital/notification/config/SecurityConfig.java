package com.hospital.notification.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import com.hospital.notification.security.QueryTicketBearerFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * R-11: 通知服务安全配置 —— 从 {@code anyRequest().permitAll()} 收紧为"ticket + 员工权限"。
 *
 * <p><b>修复的问题</b>:此前 SSE 端点 {@code /api/notify/subscribe} 匿名可达,
 * 任何人(含未登录、含患者账号)都能订阅并收到<b>全院级</b>通知,属横向越权 / 数据泄露。
 * 之前只做了资源耗尽止血(60s 超时 + 300 连接上限 + 心跳),并未解决"谁能订阅"。
 *
 * <p><b>授权策略</b>(两类端点,两条过滤链):
 * <ul>
 *   <li><b>SSE 订阅链</b> {@code /api/notify/subscribe}({@link #sseSubscribeFilterChain}):
 *       只接受 hospital-core 签发的<b>短期 ticket</b>({@code scope=sse}),并要求持有<b>任一员工权限</b>
 *       ({@code visit:entry} / {@code visit:audit} / {@code order:execute} /
 *       {@code pharmacy:dispense} / {@code charge:pay} / {@code system:admin})。
 *       仅持 {@code patient:booking} 的患者账号<b>不得</b>订阅 —— 通知台是员工端页面
 *       ({@code hospital-web/src/layouts/MainLayout.vue}),通知内容为全院级,
 *       患者能订阅即等于横向泄露。</li>
 *   <li><b>其余端点链</b>({@link #apiFilterChain}):健康检查 / 探活匿名放行,其余一律 {@code authenticated()}。</li>
 * </ul>
 * 两条链都保持 {@code csrf disabled} + {@code STATELESS}。
 *
 * <p><b>为什么订阅链单独一条</b>:ticket 会出现在 URL 上(浏览器历史 / 网关 access log / Referer),
 * 一旦泄漏也不应被当作普通令牌使用。两条链各挂一个定制 JWT 解码器,把这个不变式做成硬约束:
 * ① 订阅链要求 {@code scope=sse} —— 普通 8 小时长效 JWT 缺少该声明 → <b>401</b>(不能拿长期令牌顶替 ticket);
 * ② REST 链显式<b>拒绝</b> {@code scope=sse} —— ticket 即便被塞进 {@code Authorization} 头也无效,
 * 且 REST 链不注册 {@link QueryTicketBearerFilter},不会把 {@code ?ticket=} 提升为凭证。
 * 这样"URL 上的凭证"与"请求头里的凭证"边界清晰,与 core 的 JwtAuthFilter 口径一致。
 *
 * <p><b>密钥三档行为</b>(与 {@code hospital-file-service} 的 InternalTokenFilter、
 * {@code hospital-core} 的 JwtTokenService 口径一致):
 * <ol>
 *   <li>prod / production 且 {@code app.jwt.secret} 为空 → <b>构造期抛 {@link IllegalStateException}</b>,启动即失败;</li>
 *   <li>非 prod 且 secret 为空 → {@code log.warn} 明确提示"SSE 处于无鉴权状态,仅限本地开发",并<b>放行</b>;</li>
 *   <li>secret 已配置 → 严格校验:无 ticket / ticket 非法 / 已过期 / 非 {@code scope=sse} → 401。</li>
 * </ol>
 * 第 2 档<b>刻意</b>选择"放行"而非 fail-closed:core 在非 prod 且未注入 secret 时会生成
 * <b>随机</b>密钥,两边必然不一致;若这里 fail-closed,本地零配置联调会被全量 401 打断。
 * 这是本地开发体验与安全之间的<b>有意取舍</b>,生产必须注入 {@code APP_JWT_SECRET} 才能消除。
 *
 * <p>密钥派生与 core 完全一致:取 secret 字符串的 <b>UTF-8 原始字节</b>作为 HMAC-SHA256 密钥
 * (core 用 {@code Keys.hmacShaKeyFor(secret.getBytes(UTF_8))}),<b>不做</b> Base64 解码,
 * 否则派生出不同密钥会导致 dev 下全量 401。
 */
@Slf4j
@Configuration
public class SecurityConfig {

    /** R-11: 允许订阅通知台的员工权限(持有任一即可)。 */
    private static final String[] EMPLOYEE_AUTHORITIES = {
            "visit:entry", "visit:audit", "order:execute",
            "pharmacy:dispense", "charge:pay", "system:admin"
    };

    /** R-11: SSE ticket 的 {@code scope} 声明值;必须与 core 的 JwtTokenService.SSE_TICKET_SCOPE 一致。 */
    private static final String SSE_TICKET_SCOPE = "sse";

    /** R-11: 免鉴权的探活路径(容器 / 网关探针依赖)。 */
    private static final String[] PUBLIC_HEALTH_PATHS = {"/actuator/health", "/api/notify/health"};

    /** R-11: SSE 订阅路径 —— 仅此路径接受 ticket。 */
    private static final String SUBSCRIBE_PATH = "/api/notify/subscribe";

    /**
     * R-11: 通知读取端点 —— 同为全院级内容,只对员工开放。
     * 只收紧订阅是不够的:患者只要持任一合法 token 就能从这些端点读到全院通知,
     * 与"患者可订阅"是完全相同的横向泄露,只是换了个入口。
     */
    private static final String[] NOTIFY_READ_PATHS = {
            "/api/notify/events", "/api/notify/events/**"
    };

    /** R-11: 与 hospital-core 同一把密钥(环境变量 APP_JWT_SECRET);空表示未配置。 */
    private final String secret;

    /** R-11: 当前激活 profile,用于生产 fail-fast 判定。 */
    private final String activeProfile;

    public SecurityConfig(@Value("${app.jwt.secret:}") String secret,
                          @Value("${spring.profiles.active:dev}") String activeProfile) {
        this.secret = secret == null ? "" : secret.trim();
        this.activeProfile = activeProfile;

        // R-11: 生产环境必须注入密钥 —— 否则订阅端点在无鉴权状态下裸奔,拒绝启动。
        if (this.secret.isEmpty() && isProductionProfile()) {
            throw new IllegalStateException(
                    "[R-11] 通知服务 JWT 密钥未配置:生产环境必须通过环境变量 APP_JWT_SECRET 注入与 hospital-core"
                            + "相同的密钥,否则 /api/notify/subscribe 将处于无鉴权状态。");
        }
        // R-11: 配了密钥但过短 —— HS256 需要 ≥32 字节,提前给出可读错误而非让 Nimbus 抛底层异常。
        if (!this.secret.isEmpty()
                && this.secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "[R-11] app.jwt.secret 长度不足 32 字节,HS256 无法使用;"
                            + "请注入与 hospital-core 的 APP_JWT_SECRET 一致的 ≥32 字节密钥。");
        }
        // R-11: 非生产且未配置密钥 —— 刻意放行(见类注释第 2 档取舍说明),仅 WARN 提示。
        if (this.secret.isEmpty()) {
            log.warn("[R-11] app.jwt.secret(APP_JWT_SECRET)未配置,通知服务 SSE 处于【无鉴权状态】,仅限本地开发;"
                    + "生产必须注入与 hospital-core 相同的 APP_JWT_SECRET。");
        }
    }

    /** R-11: 是否启用严格鉴权(已配置密钥)。 */
    private boolean authEnabled() {
        return !secret.isEmpty();
    }

    /** R-11: 是否生产环境(支持逗号分隔多 profile,忽略大小写,与 core / file-service 口径一致)。 */
    private boolean isProductionProfile() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        for (String p : activeProfile.split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if ("prod".equals(t) || "production".equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** R-11: 标准解码器:签名 + 过期校验,不限制 {@code scope}(供 REST 端点使用)。 */
    private NimbusJwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        // R-11: 与 core 一致 —— secret 的 UTF-8 原始字节即 HMAC-SHA256 密钥,不做 Base64 解码。
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * R-11: SSE ticket 解码器 —— 在标准校验(签名 + 过期)之上追加 {@code scope=sse} 校验。
     * 普通 8 小时长效 JWT 不带 {@code scope} 声明,使用本解码器时会被判为无效 → 401。
     */
    private NimbusJwtDecoder sseTicketDecoder() {
        NimbusJwtDecoder decoder = jwtDecoder();
        OAuth2TokenValidator<Jwt> scopeValidator = token ->
                SSE_TICKET_SCOPE.equals(token.getClaimAsString("scope"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "该凭证仅用于 SSE 订阅", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), scopeValidator));
        return decoder;
    }

    /**
     * R-11: REST 端点解码器 —— 在标准校验之上<b>拒绝</b> {@code scope=sse} 的 ticket。
     *
     * <p>为什么需要:订阅链只负责"只接受 ticket",但反过来还得保证"ticket 不被当作普通令牌"。
     * 若 REST 链沿用无差别解码器,把 ticket 塞进 {@code Authorization: Bearer} 头就能调 REST 接口 ——
     * 那么"ticket 只用于 SSE"的不变式就只做了一半(core 的 JwtAuthFilter 是显式拒绝的,这里对齐它)。
     */
    private NimbusJwtDecoder restJwtDecoder() {
        NimbusJwtDecoder decoder = jwtDecoder();
        OAuth2TokenValidator<Jwt> notTicketValidator = token ->
                SSE_TICKET_SCOPE.equals(token.getClaimAsString("scope"))
                        ? OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "该凭证仅用于 SSE 订阅", null))
                        : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), notTicketValidator));
        return decoder;
    }

    /**
     * R-11: 把 {@code authorities} 声明映射为 Spring Security 权限。
     * 选用 {@link SimpleGrantedAuthority}(不加 {@code SCOPE_} 前缀),与 hospital-core 的 JwtAuthFilter
     * 保持一致 —— 这样同一份 {@code hasAnyAuthority("visit:entry", ...)} 规则可以在两侧复用。
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> authorities = jwt.getClaimAsStringList("authorities");
            if (authorities == null) {
                return List.<GrantedAuthority>of();
            }
            return authorities.stream()
                    .filter(StringUtils::hasText)
                    .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                    .toList();
        });
        return converter;
    }

    /**
     * R-11: SSE 订阅链(Order 1)—— 只覆盖 {@code /api/notify/subscribe}。
     * 要求短期 ticket({@code scope=sse})+ 任一员工权限;未带凭证 401,患者权限 403。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain sseSubscribeFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(SUBSCRIBE_PATH)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // R-11: EventSource 预检放行,避免被打断
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                if (authEnabled()) {
                    // R-11: 仅"员工权限持有者"可订阅;仅 patient:booking 的账号 → 403
                    auth.anyRequest().hasAnyAuthority(EMPLOYEE_AUTHORITIES);
                } else {
                    // R-11: 未配置密钥的非生产环境 —— 放行(见类注释三档行为说明)
                    auth.anyRequest().permitAll();
                }
            });

        if (authEnabled()) {
            http.oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                        .decoder(sseTicketDecoder())
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // R-11: 把 ?ticket= 提升为 Authorization: Bearer 后,交给标准链路校验
                .addFilterBefore(new QueryTicketBearerFilter(), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * R-11: 其余端点链(Order 2)—— 健康检查 / 探活放行,其余需认证。
     * 本链<b>不</b>提升 {@code ?ticket=},因此 ticket 无法用于 REST 端点。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // R-11: CORS 预检放行
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                // R-11: 健康检查 / 探活免鉴权
                auth.requestMatchers(PUBLIC_HEALTH_PATHS).permitAll();
                if (authEnabled()) {
                    // R-11: 通知内容为全院级 —— 读取端点与订阅同权限,仅员工可读。
                    // 仅收紧订阅是不够的:患者拿到任一合法 token 就能从 /events 读到全院通知,
                    // 与"患者可订阅"是同一个横向泄露,只是换了个入口。
                    auth.requestMatchers(NOTIFY_READ_PATHS).hasAnyAuthority(EMPLOYEE_AUTHORITIES);
                    auth.anyRequest().authenticated();
                } else {
                    auth.anyRequest().permitAll();
                }
            });

        if (authEnabled()) {
            // R-11: 用 restJwtDecoder() 而非 jwtDecoder() —— 显式拒绝 scope=sse 的 ticket,
            // 与 core 的 JwtAuthFilter 保持同一不变式:"能放在 URL 上的凭证不得当普通令牌用"。
            http.oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                    .decoder(restJwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())));
        }
        return http.build();
    }
}
