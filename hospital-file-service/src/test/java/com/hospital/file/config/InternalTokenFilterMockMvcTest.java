package com.hospital.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-27:文件服务"配置了内部令牌"时的端到端鉴权守护(@SpringBootTest + MockMvc)。
 *
 * <p>守护语义(见 InternalTokenFilter / SecurityConfig 的 R-03 注释):
 * 配置了 {@code file.internal-token} 之后,所有非探活、非 OPTIONS 请求都必须携带
 * 匹配的 {@code X-Internal-Token},否则 401 —— 这是文件服务唯一的对外防线。
 *
 * <p>MinioClient 用 @MockBean 替换:MinioConfig 里的 initBucket 是 CommandLineRunner,
 * 真实客户端会在无 MinIO 的环境下抛连接异常导致上下文启动失败;
 * 该 mock 只影响文件读写,不影响 Security 过滤器链,因此不削弱本测试的守护价值。
 */
@SpringBootTest(properties = "file.internal-token=test-token")
@AutoConfigureMockMvc
class InternalTokenFilterMockMvcTest {

    // R-27: 必须与 @SpringBootTest 注入的 file.internal-token 保持一致
    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MinioClient minioClient;

    @Test
    @DisplayName("R-27 用例A:已配置令牌但请求不带 X-Internal-Token → 401")
    void requestWithoutTokenShouldBeUnauthorized() throws Exception {
        int status = mockMvc.perform(get("/api/files").param("patientId", "1"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("R-27 用例B:X-Internal-Token 不匹配 → 401")
    void requestWithWrongTokenShouldBeUnauthorized() throws Exception {
        int status = mockMvc.perform(get("/api/files")
                        .header(InternalTokenFilter.TOKEN_HEADER, "not-the-right-token")
                        .param("patientId", "1"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("R-27 用例C:令牌匹配 → 过滤器放行(不得是 401;实际状态码取决于后端)")
    void requestWithMatchedTokenShouldNotBeUnauthorized() throws Exception {
        int status = mockMvc.perform(get("/api/files")
                        .header(InternalTokenFilter.TOKEN_HEADER, TOKEN)
                        .param("patientId", "1"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(401);
    }

    @Test
    @DisplayName("R-27 用例C2:令牌匹配的请求确实抵达了 Controller(未配置时是 200,而非被过滤器短路)")
    void requestWithMatchedTokenShouldReachHealthEndpoint() throws Exception {
        int status = mockMvc.perform(get("/api/files/health")
                        .header(InternalTokenFilter.TOKEN_HEADER, TOKEN))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("R-27 用例E:探活端点 /api/files/health 不带令牌也不被 401")
    void healthEndpointShouldNotRequireToken() throws Exception {
        int status = mockMvc.perform(get("/api/files/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("R-27:OPTIONS 预检不带令牌也必须放行(否则浏览器端 Upload/Download 会被预检打断)")
    void optionsPreflightShouldNotRequireToken() throws Exception {
        int status = mockMvc.perform(options("/api/files"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(401);
    }
}
