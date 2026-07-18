package com.hospital.core.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring 调度基础设施(@Scheduled)。
 * 仅启用 Spring 内置能力,不引入外部调度中心。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
