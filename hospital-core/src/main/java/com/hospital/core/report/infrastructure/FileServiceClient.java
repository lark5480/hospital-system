package com.hospital.core.report.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 内部文件服务客户端:封装对 hospital-file-service 的上传/下载调用。
 * 文件服务为纯 MinIO 薄封装,元数据随对象存于 user-metadata。
 */
@Slf4j
@Component
public class FileServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public FileServiceClient(RestTemplate restTemplate,
                             @Value("${file.service.base-url:http://localhost:8103}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /**
     * 上传 PDF 字节流到文件服务,返回 MinIO 对象名(如 reports/123.pdf)。
     * objectName 由调用方指定,保证幂等(重复上传覆盖同一对象)。
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
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        String url = baseUrl + "/api/files/upload";
        ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);
        if (resp.getBody() == null || resp.getBody().get("objectName") == null) {
            throw new IllegalStateException("文件服务上传失败: " + resp.getStatusCode());
        }
        return (String) resp.getBody().get("objectName");
    }

    /** 从文件服务下载对象字节(用于 core 流式代理返回给患者)。 */
    public byte[] download(String objectName, Long patientId) {
        String url = baseUrl + "/api/files/" + objectName
                + (patientId != null ? "?patientId=" + patientId : "");
        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new IllegalStateException("文件服务下载失败: " + resp.getStatusCode());
            }
            return resp.getBody();
        } catch (RestClientException ex) {
            // 统一为 IllegalStateException,供调用方(如 PatientController)做"重新生成+重传"兜底
            throw new IllegalStateException("文件服务下载失败: " + ex.getMessage(), ex);
        }
    }
}
