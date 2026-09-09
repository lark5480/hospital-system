package com.hospital.core.report.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 内部文件服务客户端:封装对 hospital-file-service 的上传/下载调用。
 * 文件服务为纯 MinIO 薄封装,元数据随对象存于 user-metadata。
 *
 * <p>R-03:文件服务已启用 {@code X-Internal-Token} 令牌校验,本客户端所有请求
 * 统一携带该请求头;令牌取自 {@code file.internal-token}(未配置则为空串,
 * 与 file-service 的"未配置即放行"保持一致,便于本地零配置联调)。
 * 同时 download 必须显式传 {@code patientId}(file-service 侧已改为必填)。
 */
@Slf4j
@Component
public class FileServiceClient {

    /** R-03: 内部调用令牌请求头,与 file-service InternalTokenFilter 保持一致。 */
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public FileServiceClient(RestTemplate restTemplate,
                             @Value("${file.service.base-url:http://localhost:8103}") String baseUrl,
                             @Value("${file.internal-token:${FILE_INTERNAL_TOKEN:}}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    /**
     * 上传 PDF 字节流到文件服务,返回 MinIO 对象名(如 reports/123.pdf)。
     * objectName 由调用方指定,保证幂等。
     *
     * <p>R-30:file-service 已禁止同名覆盖(409)。这里把 409 视为"对象已存在即已上传成功",
     * 保持本方法的幂等语义(PDF 重新生成触发的重复上传不会抛异常)。
     * TODO(P1):报告内容变更后需要真正更新归档文件,应改为版本化对象名
     * (如 reports/{reportId}/{version}.pdf)或提供带审计的显式覆盖接口,由主席裁决。
     */
    public String uploadPdf(byte[] pdf, Long patientId, Long reportId, String objectName, String originalName) {
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return originalName;
            }
        };
        body.add("file", resource);
        body.add("patientId", patientId);
        body.add("reportId", reportId);
        body.add("bizType", "REPORT");
        body.add("objectName", objectName);
        body.add("originalName", originalName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applyInternalToken(headers);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        String url = baseUrl + "/api/files/upload";
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);
            if (resp.getBody() == null || resp.getBody().get("objectName") == null) {
                throw new IllegalStateException("文件服务上传失败: " + resp.getStatusCode());
            }
            return (String) resp.getBody().get("objectName");
        } catch (HttpClientErrorException ex) {
            // R-30: 409 表示对象已存在且禁止覆盖,等同于"已上传",保持幂等
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                log.warn("[file] 对象已存在,按幂等处理跳过覆盖: {}", objectName);
                return objectName;
            }
            throw ex;
        }
    }

    /**
     * 从文件服务下载对象字节(用于 core 流式代理返回给患者)。
     *
     * <p>R-03: patientId 为必填,file-service 侧会做归属校验(owner 不一致或无归属 → 403);
     * 这里若为空直接快速失败,避免产生必然 400 的请求。
     */
    public byte[] download(String objectName, Long patientId) {
        if (patientId == null) {
            // R-03: file-service 的 patientId 已改必填;没有患者上下文的调用点必须先补齐再调用
            // TODO(P1):若后续出现"运营/医生侧无 patientId 取件"场景,需改为服务端按角色授权,
            // 而不是放开 patientId,由主席裁决。
            throw new IllegalArgumentException("file-service 下载要求 patientId 必填(R-03)");
        }
        // R-03: 统一走 URI 构建,避免手工拼接造成的注入/编码问题
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/api/files/")
                .path(objectName)
                .queryParam("patientId", patientId)
                .encode()
                .toUriString();
        try {
            HttpHeaders headers = new HttpHeaders();
            applyInternalToken(headers);
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new IllegalStateException("文件服务下载失败: " + resp.getStatusCode());
            }
            return resp.getBody();
        } catch (RestClientException ex) {
            // 统一为 IllegalStateException,供调用方(如 PatientController)做"重新生成+重传"兜底
            throw new IllegalStateException("文件服务下载失败: " + ex.getMessage(), ex);
        }
    }

    /** R-03: 给内部调用请求统一带上内部令牌(未配置时为空串,file-service 侧放行并告警)。 */
    private void applyInternalToken(HttpHeaders headers) {
        if (!internalToken.isEmpty()) {
            headers.set(TOKEN_HEADER, internalToken);
        }
    }
}
