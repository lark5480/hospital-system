package com.hospital.file.api;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文件服务:MinIO 薄封装 + 对象级业务元数据(user-metadata)。
 *
 * <p>设计取舍:不引入独立数据库,文件元数据随对象一起存进 MinIO user-metadata,
 * 列表查询通过 listObjects + statObject 回读。保持"薄封装、可独立伸缩"定位。
 * 患者归属守卫在调用方(core 的 /api/patient/**)强制;本服务 download 要求
 * <b>必填</b> patientId 做纵深防御,即便被人直连也无法跨患者取件(R-03)。
 *
 * <p>R-30 加固:对象名白名单校验(拒绝路径穿越/控制字符)、同名对象禁止覆盖(防报告投毒)、
 * Content-Disposition 使用 Spring 标准 API 编码(杜绝响应头注入)。
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    /** R-30: 对象名白名单:仅允许字母数字与 / _ . -,不允许空格、反斜杠、控制字符等。 */
    private static final Pattern OBJECT_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9/_.-]+$");

    /** R-30: 对象名最大长度,防止超长名导致 MinIO/日志异常。 */
    private static final int OBJECT_NAME_MAX_LEN = 512;

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

        // R-30: 对象名完全由调用方可控,必须白名单校验,否则可写穿目录(../)、注入控制字符
        if (!isSafeObjectName(finalName)) {
            log.warn("[file] 拒绝非法对象名: {}", finalName);
            return ResponseEntity.badRequest().body(Map.of("error", "objectName 非法"));
        }

        // R-30: 同名对象已存在则拒绝覆盖,防止已归档的报告 PDF 被"投毒"替换
        if (objectExists(finalName)) {
            log.warn("[file] 拒绝覆盖已存在对象: {}", finalName);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "对象已存在,禁止覆盖"));
        }

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
        // R-30: 用 LinkedHashMap 而非 Map.of,后者遇到 null 值(如未传 reportId)会抛 NPE → 500
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("objectName", finalName);
        resp.put("bucket", bucket);
        resp.put("patientId", patientId);
        resp.put("reportId", reportId);
        resp.put("bizType", bizType);
        return ResponseEntity.ok(resp);
    }

    /** 文件下载:从 MinIO 流式返回。
     *  R-03: patientId 由可选改为<b>必填</b>,且对象无归属元数据(owner == null)时一律 403,
     *  不再允许"不传参数即跳过校验"拿走任意患者报告 PDF。
     *  路由用 {*objectName}(Spring Boot 3 PathPatternParser 的余数捕获)以匹配
     *  含斜杠的对象名(如 reports/10.pdf);捕获值带前导斜杠,需裁掉。 */
    @GetMapping("/{*objectName}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String objectName,
            @RequestParam(value = "patientId", required = true) Long patientId) throws Exception {
        if (objectName.startsWith("/")) objectName = objectName.substring(1);

        // R-30: 对象名白名单校验,阻断路径穿越与控制字符
        if (!isSafeObjectName(objectName)) {
            log.warn("[file] 拒绝非法对象名下载: {}", objectName);
            return ResponseEntity.badRequest().body(null);
        }

        StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(objectName).build());
        String owner = stat.userMetadata() != null ? stat.userMetadata().get("patientid") : null;
        // R-03: 无归属对象一律拒绝(历史数据/非患者业务对象需重新带 patientid 元数据上传)
        if (owner == null || !owner.equals(String.valueOf(patientId))) {
            log.warn("[file] 归属校验失败: object={} 请求 patientId={} 归属={}",
                    objectName, patientId, owner);
            return ResponseEntity.status(403).body(null);
        }

        GetObjectResponse obj = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectName).build());
        String ct = obj.headers().get("Content-Type");
        // R-30: 用 Spring 标准 API 生成 Content-Disposition,
        // 自动做 RFC 5987/2231 编码,杜绝用字符串拼接导致的响应头注入(CRLF)
        String fileName = objectName.contains("/")
                ? objectName.substring(objectName.lastIndexOf('/') + 1)
                : objectName;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ct != null ? ct : "application/octet-stream"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
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

    /** R-30: 对象名安全校验:非空、长度受限、白名单字符、不含 ".." 与控制字符。 */
    private boolean isSafeObjectName(String name) {
        if (name == null || name.isBlank() || name.length() > OBJECT_NAME_MAX_LEN) {
            return false;
        }
        // R-30: 路径穿越(../)与 Windows 反斜杠
        if (name.contains("..") || name.contains("\\")) {
            return false;
        }
        // R-30: 控制字符(含 CR/LF/NUL),防止响应头与日志注入
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20 || name.charAt(i) == 0x7F) {
                return false;
            }
        }
        return OBJECT_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * R-30: 判断对象是否已存在。
     * MinIO 对不存在的对象返回 NoSuchKey/NoSuchObject(以异常形式抛出),视为"不存在";
     * 其他异常原样抛出(fail-closed,不让网络/权限异常被误判为可覆盖)。
     */
    private boolean objectExists(String objectName) throws Exception {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectName).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : "";
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            throw e;
        }
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
