package com.hospital.file.api;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件服务:MinIO 薄封装 + 对象级业务元数据(user-metadata)。
 *
 * <p>设计取舍:不引入独立数据库,文件元数据随对象一起存进 MinIO user-metadata,
 * 列表查询通过 listObjects + statObject 回读。保持"薄封装、可独立伸缩"定位。
 * 患者归属守卫在调用方(core 的 /api/patient/**)强制;本服务 download 额外支持
 * 可选 patientId 参数做纵深防御,即便被人直连也能拦下跨患者取件。
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final MinioClient minioClient;

    @Value("${minio.bucket:hospital}")
    private String bucket;

    /** 上传文件,并将业务元数据写入 MinIO user-metadata(键统一小写)。 */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "objectName", required = false) String objectName,
            @RequestParam(value = "patientId", required = false) Long patientId,
            @RequestParam(value = "reportId", required = false) Long reportId,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "originalName", required = false) String originalName) throws Exception {
        String finalName = (objectName != null && !objectName.isBlank())
                ? objectName
                : UUID.randomUUID() + "-" + file.getOriginalFilename();

        Map<String, String> meta = new LinkedHashMap<>();
        if (patientId != null) meta.put("patientid", String.valueOf(patientId));
        if (reportId != null) meta.put("reportid", String.valueOf(reportId));
        if (bizType != null) meta.put("biztype", bizType);
        if (originalName != null) meta.put("originalname", originalName);
        meta.put("createdat", Instant.now().toString());

        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(finalName)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .userMetadata(meta)
                    .build());
        }
        log.debug("[file] 上传对象 {} 元数据 {}", finalName, meta);
        return ResponseEntity.ok(Map.of(
                "objectName", finalName,
                "bucket", bucket,
                "patientId", patientId,
                "reportId", reportId,
                "bizType", bizType));
    }

    /** 文件下载:从 MinIO 流式返回;可选 patientId 做归属纵深防御。
     *  路由用 {*objectName}(Spring Boot 3 PathPatternParser 的余数捕获)以匹配
     *  含斜杠的对象名(如 reports/10.pdf);捕获值带前导斜杠,需裁掉。 */
    @GetMapping("/{*objectName}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String objectName,
            @RequestParam(value = "patientId", required = false) Long patientId) throws Exception {
        if (objectName.startsWith("/")) objectName = objectName.substring(1);
        if (patientId != null) {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectName).build());
            String owner = stat.userMetadata() != null ? stat.userMetadata().get("patientid") : null;
            if (owner != null && !owner.equals(String.valueOf(patientId))) {
                return ResponseEntity.status(403).body(null);
            }
        }
        GetObjectResponse obj = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectName).build());
        String ct = obj.headers().get("Content-Type");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ct != null ? ct : "application/octet-stream"))
                .header("Content-Disposition", "inline; filename=\"" + objectName + "\"")
                .body(new InputStreamResource(obj));
    }

    /** 文件列表:支持按 bizType(映射到对象前缀)与 patientId 过滤。 */
    @GetMapping
    public ResponseEntity<List<FileMeta>> list(
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "patientId", required = false) Long patientId) throws Exception {
        return ResponseEntity.ok(queryFiles(bizType, patientId));
    }

    /** C 端"我的文件":仅返回指定患者的对象。 */
    @GetMapping("/mine")
    public ResponseEntity<List<FileMeta>> mine(
            @RequestParam(value = "patientId", required = false) Long patientId) throws Exception {
        return ResponseEntity.ok(queryFiles(null, patientId));
    }

    private List<FileMeta> queryFiles(String bizType, Long patientId) throws Exception {
        String prefix = (bizType != null && !bizType.isBlank()) ? bizType.toLowerCase() + "/" : "";
        List<FileMeta> result = new ArrayList<>();
        Iterable<io.minio.Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(prefix).recursive(true).build());
        for (io.minio.Result<Item> r : objects) {
            Item item = r.get();
            if (item.isDir()) continue;
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(item.objectName()).build());
            Map<String, String> um = stat.userMetadata() != null ? stat.userMetadata() : Map.of();
            Long metaPatient = um.get("patientid") != null ? Long.parseLong(um.get("patientid")) : null;
            if (patientId != null && (metaPatient == null || !metaPatient.equals(patientId))) continue;
            result.add(new FileMeta(
                    item.objectName(),
                    um.get("biztype"),
                    metaPatient,
                    um.get("reportid") != null ? Long.parseLong(um.get("reportid")) : null,
                    um.get("originalname"),
                    stat.size(),
                    item.lastModified().toString()));
        }
        return result.stream()
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .collect(Collectors.toList());
    }

    /** 文件元数据(read model)。 */
    public record FileMeta(
            String objectName,
            String bizType,
            Long patientId,
            Long reportId,
            String originalName,
            long size,
            String createdAt
    ) {
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "file", "status", "up");
    }
}
