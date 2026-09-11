package com.hospital.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-27 用例D:守护"未配置内部令牌时放行"的本地开发行为。
 *
 * <p>{@code file.internal-token} 留空(默认值,即本地零配置启动)时,
 * InternalTokenFilter 必须继续放行并打印 WARN,而不是把请求全部 401,
 * 否则本地 / 演示环境一启动就全站不可用。
 *
 * <p>注意:这只是"本地可跑"的兜底语义,生产由 InternalTokenFilter 的
 * prod fail-fast(未配置令牌直接拒绝启动)兜住,两者不冲突。
 * 若后续有人把"未配置即拒绝"改成默认行为,本用例会失败,需要同步评估本地启动体验。
 */
@SpringBootTest(properties = "file.internal-token=")
@AutoConfigureMockMvc
class InternalTokenNotConfiguredMockMvcTest {

    // R-27 用例D:令牌留空 = 本地零配置启动,请求不得被 401
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MinioClient minioClient;

    @Test
    @DisplayName("R-27 用例D:未配置令牌时,不带 X-Internal-Token 的请求也不被 401")
    void requestWithoutTokenShouldPassWhenTokenNotConfigured() throws Exception {
        int status = mockMvc.perform(get("/api/files").param("patientId", "1"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(401);
    }

    @Test
    @DisplayName("R-27 用例D2:未配置令牌时,探活端点照常可用")
    void healthEndpointShouldBeAvailableWhenTokenNotConfigured() throws Exception {
        int status = mockMvc.perform(get("/api/files/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }
}
