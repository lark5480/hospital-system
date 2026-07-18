package com.hospital.core.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

/**
 * 平台基础配置:
 * - 启用异步(@EnableAsync),供报告 PDF 生成等重活异步执行,不阻塞主流程。
 * - RestTemplate:供 core 调用内部文件服务(file-service)上传/下载 PDF。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "reportPdfExecutor")
    public TaskExecutor reportPdfExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-pdf-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
