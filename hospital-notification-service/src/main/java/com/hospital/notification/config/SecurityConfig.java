package com.hospital.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 通知服务安全配置:内部消费端,仅接收 Gateway 透传请求。
 * 默认放行全部请求;生产态若需本服务独立验签,可在此补 oauth2ResourceServer。
 *
 * <p>R-13 止血说明:本服务 SSE 端点(/api/notify/subscribe)原本为 0L 永不超时 + 无界订阅集合,
 * 在 permitAll() 下匿名即可无限开连接,属于资源耗尽型 DoS。现已通过"60s 超时 + 300 连接上限 +
 * 15s 心跳 + 死连接清理"完成止血,单实例最多占用 300 条长连接,死连接 60s 内必然被回收。
 *
 * <p>【P1 待办】鉴权仍然缺失:上述措施只解决了资源耗尽,没有解决"任何人可订阅全部通知"的越权问题。
 * 生产环境必须二选一:
 *  1) 接入与 hospital-core 相同的 JWT 验签(oauth2ResourceServer / 自定义 Filter),再对 SSE 端点加鉴权;
 *  2) 在网络层隔离 8102 端口(仅允许网关与内网访问,禁止公网直连)。
 * 注意:在补齐网关统一鉴权前不要直接改这里的 permitAll(),否则前端 notifySSE.ts 直连会被全量 401 打断。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
