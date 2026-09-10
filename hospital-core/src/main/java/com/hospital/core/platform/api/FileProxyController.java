package com.hospital.core.platform.api;

import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;
import com.hospital.core.report.infrastructure.FileServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 文件服务代理:把 file-service 从公网入口收回到 core 身后(R-62)。
 *
 * <h3>为什么必须有这一层</h3>
 * 原先网关直接把 {@code /api/files/**} 转发到 file-service,并给它注入内部调用令牌。
 * 但 file-service <b>没有用户体系</b>:它的归属校验只能拿"调用方自报的 patientId"
 * 去比对对象元数据。而它的 {@code list} 接口在不传 patientId 时返回<b>全部</b>对象
 * (每条都带 patientId),于是形成一条闭环:
 * <pre>
 *   匿名 GET /api/files                    → 拿到全部 objectName + patientId
 *   匿名 GET /api/files/{objectName}?patientId=&lt;上一步拿到的&gt; → 归属校验通过 → 患者报告 PDF
 * </pre>
 * 换句话说,校验所需的"钥匙"由 list 自己送了出去,纵深防御等于不存在
 * (网关默认 profile 即 {@code permitAll},且仓库中不存在 {@code application-iam.yml})。
 *
 * <h3>收口后的边界</h3>
 * <ul>
 *   <li>网关不再有 {@code /api/files/**} 路由,file-service 只在内网可达;</li>
 *   <li>所有文件访问经本控制器,受 core 的 JWT 鉴权链约束;</li>
 *   <li>归属判定在有身份上下文的地方做 —— 患者角色被<b>强制覆盖</b>为本人 patientId
 *       (与 {@code ReportController#resolveReadablePatientId} 同款实现),不存在"改 id 看他人文件"的路径。</li>
 * </ul>
 *
 * <p>注意:本控制器只做"鉴权 + 归属 + 转发",不做业务语义;文件类型/大小/对象名合法性
 * 仍由 file-service 侧校验({@code R-30})。
 */
@Tag(name = "文件代理", description = "经 core 统一收口的文件上传 / 查询 / 下载")
@RestController
@RequestMapping("/api/core/files")
@RequiredArgsConstructor
@Slf4j
public class FileProxyController {

    private final FileServiceClient fileServiceClient;
    private final PatientService patientService;
    private final StaffService staffService;

    @Operation(summary = "查询文件列表")
    // R-62: 读接口。患者(patient:booking)会被强制收窄为本人文件;医护 / 管理员可按条件查看。
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String bizType,
                                  @RequestParam(required = false) Long patientId) {
        Long effectivePatientId = resolveReadablePatientId(patientId);
        // 非员工(患者)解析不到自己的档案 → 拒绝,而不是放行成全量
        if (effectivePatientId == null && !isStaff()) {
            log.warn("[R-62] 拒绝无患者档案主体的文件列表请求: user={}",
                    CurrentUserResolver.resolveUsername());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return ResponseEntity.ok(fileServiceClient.list(bizType, effectivePatientId));
        } catch (Exception ex) {
            return fileServiceError("查询文件列表", ex);
        }
    }

    @Operation(summary = "下载文件")
    // R-62: 下载同样要求能确定归属;患者被强制收窄为本人。
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/{*objectName}")
    public ResponseEntity<?> download(@PathVariable String objectName,
                                     @RequestParam(required = false) Long patientId) {
        // Spring Boot 3 的余数捕获带前导斜杠,裁掉
        if (objectName.startsWith("/")) {
            objectName = objectName.substring(1);
        }
        Long effectivePatientId = resolveReadablePatientId(patientId);
        if (effectivePatientId == null) {
            // 员工也没传 patientId:无法确定归属,而 file-service 要求 owner 一致,直接拒绝而不猜
            log.warn("[R-62] 下载缺少归属 patientId: object={} user={}",
                    objectName, CurrentUserResolver.resolveUsername());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            byte[] bytes = fileServiceClient.download(objectName, effectivePatientId);
            String filename = objectName.contains("/")
                    ? objectName.substring(objectName.lastIndexOf('/') + 1)
                    : objectName;
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(filename, StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(bytes);
        } catch (Exception ex) {
            return fileServiceError("下载文件", ex);
        }
    }

    @Operation(summary = "上传文件")
    // R-62: 上传是写操作,不对患者(patient:booking)开放 —— 文件管理台属医护 / 管理员能力。
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin')")
    @AuditLog(action = "UPLOAD_FILE")
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) String objectName,
                                    @RequestParam(required = false) String bizType,
                                    @RequestParam(required = false) Long patientId,
                                    @RequestParam(required = false) Long reportId,
                                    @RequestParam(required = false) String originalName) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "上传文件为空"));
        }
        try {
            return ResponseEntity.ok(fileServiceClient.upload(
                    file, objectName, bizType, patientId, reportId, originalName));
        } catch (Exception ex) {
            return fileServiceError("上传文件", ex);
        }
    }

    /**
     * R-62: 解析本次请求"实际可用于查询/下载的患者 ID"。
     *
     * <p>员工(医护 / 管理员)允许按入参查询;患者角色被强制覆盖为本人 patientId,
     * 解析不到本人档案则返回 null,由调用方决定 403。
     * 与 {@code ReportController} 的同名私有方法保持一致的语义。
     *
     * @param requestedPatientId 前端传入的 patientId(可能为 null,也可能被恶意伪造)
     * @return 允许使用的患者 ID;null 表示"无权或无法确定归属"
     */
    private Long resolveReadablePatientId(Long requestedPatientId) {
        if (isStaff()) {
            return requestedPatientId;
        }
        return patientService.currentPatientId();
    }

    /** R-62: 当前登录主体是否为员工(按手机号在 staff 表命中);患者账号返回 false。 */
    private boolean isStaff() {
        String phone = CurrentUserResolver.resolveUsername();
        return phone != null && staffService.findByPhone(phone) != null;
    }

    /**
     * R-62: 把文件服务的错误翻译成合适的 HTTP 状态,避免把下游异常直接暴露成 500。
     *
     * <p>file-service 的 403 表示"对象归属与请求 patientId 不一致";409 表示同名对象已存在
     * (管理台需要看到真实冲突);404 表示对象不存在。其余一律 502 —— 是对下游不可用,
     * 而不是本服务出错,前端提示也更准确。
     */
    private ResponseEntity<?> fileServiceError(String action, Exception ex) {
        HttpClientErrorException httpError = null;
        if (ex instanceof HttpClientErrorException direct) {
            httpError = direct;
        } else if (ex.getCause() instanceof HttpClientErrorException wrapped) {
            httpError = wrapped;
        }
        if (httpError != null) {
            HttpStatusCode status = httpError.getStatusCode();
            if (status.value() == HttpStatus.CONFLICT.value()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "对象已存在,禁止覆盖"));
            }
            if (status.value() == HttpStatus.FORBIDDEN.value()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "该文件不属于指定患者"));
            }
            if (status.value() == HttpStatus.NOT_FOUND.value()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "文件不存在"));
            }
        }
        log.error("[R-62] {} 失败: {}", action, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "文件服务不可用"));
    }
}
