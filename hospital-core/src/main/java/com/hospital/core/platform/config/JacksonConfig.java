package com.hospital.core.platform.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * 全局 Jackson 日期/时间序列化配置。
 * <p>
 * 统一所有 LocalDateTime / LocalDate / LocalTime 的 JSON 输出格式,
 * 避免接口返回 "2026-07-18T09:11:39.660362" 这类带 T 与微秒的 ISO 原始格式,
 * 改为更易读的 "2026-07-18 09:11:39" / "2026-07-18" / "09:11:39"。
 * <p>
 * 该 Customizer 会作用于 Spring Boot 自动装配的全局 ObjectMapper,
 * 对所有 REST 控制器返回值、@RequestBody 反序列化同时生效。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIME_PATTERN = "HH:mm:ss";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_PATTERN);
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern(TIME_PATTERN);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer dateTimeJacksonCustomizer() {
        return builder -> builder
                .serializers(
                        new LocalDateTimeSerializer(DATE_TIME_FORMATTER),
                        new LocalDateSerializer(DATE_FORMATTER),
                        new LocalTimeSerializer(TIME_FORMATTER))
                .deserializers(
                        new LocalDateTimeDeserializer(DATE_TIME_FORMATTER),
                        new LocalDateDeserializer(DATE_FORMATTER),
                        new LocalTimeDeserializer(TIME_FORMATTER));
    }
}
