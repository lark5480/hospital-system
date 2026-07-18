package com.hospital.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 本地开发态(未激活 iam profile)安全配置:放行全部请求,作为反向代理直接转发到下游服务。
 * 激活 iam profile 时由 application-iam.yml 的 TokenRelay 网关过滤器接管令牌中继。
 */
@Configuration
@EnableWebFluxSecurity
@Profile("!iam")
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain devFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchange -> exchange.anyExchange().permitAll());
        return http.build();
    }
}
