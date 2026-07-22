package com.hospital.notification.config;

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
 * 统一 LocalDateTime / LocalDate / LocalTime 输出为人类可读格式
 * (如 "2026-07-18 09:11:39"),避免 ISO 带 T 与微秒的原始格式。
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
