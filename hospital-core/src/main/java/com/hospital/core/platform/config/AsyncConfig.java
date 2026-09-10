package com.hospital.core.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 平台基础配置:
 * - 启用异步(@EnableAsync),供报告 PDF 生成等重活异步执行,不阻塞主流程。
 * - R-44:新增审计日志专用线程池 {@code auditLogExecutor},把审计写入移出业务线程。
 * - RestTemplate:供 core 调用内部文件服务(file-service)上传/下载 PDF。
 *
 * <p>R-22:RestTemplate 必须设置连接/读超时,否则 file-service 挂起时
 * PDF 线程与 Tomcat 线程会永久阻塞(等同于线程泄漏);线程池满时改用调用方线程执行,
 * 触发背压而不是静默丢弃任务。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** R-22: 内部文件服务连接超时(ms)。 */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** R-22: 内部文件服务读超时(ms),PDF 上传/下载体积较大,给 15s。 */
    private static final int READ_TIMEOUT_MS = 15000;

    @Bean(name = "reportPdfExecutor")
    public TaskExecutor reportPdfExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-pdf-");
        // R-22: 队列满时由提交任务的线程自己执行,形成背压,避免 AbortPolicy 静默丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * R-44: 审计日志专用线程池。审计写入(可能带磁盘/网络 IO)不应阻塞业务请求线程,
     * 但审计又绝不能因异步而静默丢失,故采用"小线程数 + 大队列 + 背压"策略:
     * <ul>
     *   <li>core 1 / max 2:避免与业务线程争抢;</li>
     *   <li>queue 1000:平滑吸收审计高峰;</li>
     *   <li>守护线程:不阻止 JVM 退出;</li>
     *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy}:队列满时由提交线程(业务线程)自己执行,
     *       形成背压而不是丢弃任务。</li>
     * </ul>
     * 兜底:即便本池不可用(拒绝/关闭),AuditLogAspect 也会降级为同步写入。
     */
    @Bean(name = "auditLogExecutor")
    public TaskExecutor auditLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("audit-log-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        // R-22: 显式设置连接/读超时,杜绝无限期阻塞(file-service 挂起时 3s/15s 快速失败)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }
}
