package com.hospital.core.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.report.infrastructure.FileServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * R-62:文件代理层的归属校验与错误翻译测试(纯 Mockito,不启 Spring 上下文)。
 *
 * <p>这层是 R-62 的修复主体,必须锁住三条契约:
 * <ol>
 *   <li><b>患者的入参一律不可信</b>:无论前端传谁的 patientId(或不传),都要被强制收窄为本人;
 *       解析不到本人档案时拒绝,而不是"放行成全量"。</li>
 *   <li><b>下载必须能确定归属</b>:员工不传 patientId 时返回 403 而不是猜一个值,
 *       否则会退化成"任意员工可下载任意患者文件"。</li>
 *   <li><b>下游错误不得暴露成 500</b>:file-service 的 403/409/404 要透传为同义状态,
 *       其余(连接失败等)归为 502。</li>
 * </ol>
 *
 * <p>注意:{@code @PreAuthorize} 属于 Spring 方法安全,不在无上下文单测范围内
 * (它由 core 的 SecurityConfig 统一启用,已在其它用例覆盖)。
 * 这里专门验证"注解之后的归属判定逻辑"。
 */
class FileProxyControllerTest {

    private static final String PATIENT_PHONE = "13800000001";
    private static final String STAFF_PHONE = "13900000001";
    private static final long OWN_PATIENT_ID = 7L;
    private static final long OTHER_PATIENT_ID = 999L;

    private FileServiceClient fileServiceClient;
    private PatientService patientService;
    private StaffService staffService;
    private FileProxyController controller;

    @BeforeEach
    void setUp() {
        fileServiceClient = mock(FileServiceClient.class);
        patientService = mock(PatientService.class);
        staffService = mock(StaffService.class);
        controller = new FileProxyController(fileServiceClient, patientService, staffService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("R-62 用例A:患者伪造他人 patientId 查列表 → 被强制覆盖为本人")
    void listAsPatientForcesOwnPatientId() {
        givenPatient();
        when(fileServiceClient.list(eq("REPORT"), eq(OWN_PATIENT_ID))).thenReturn(List.of());

        ResponseEntity<?> resp = controller.list("REPORT", OTHER_PATIENT_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 关键断言:下游拿到的是本人 id,而不是前端伪造的 999
        verify(fileServiceClient).list("REPORT", OWN_PATIENT_ID);
        verify(fileServiceClient, never()).list(any(), eq(OTHER_PATIENT_ID));
    }

    @Test
    @DisplayName("R-62 用例B:患者不传 patientId → 同样收窄为本人,不放行全量")
    void listAsPatientWithoutParamStillScopedToOwn() {
        givenPatient();
        when(fileServiceClient.list(isNull(), eq(OWN_PATIENT_ID))).thenReturn(List.of());

        ResponseEntity<?> resp = controller.list(null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(fileServiceClient).list(null, OWN_PATIENT_ID);
    }

    @Test
    @DisplayName("R-62 用例C:患者无绑定档案 → 403,且完全不触碰文件服务")
    void listAsPatientWithoutRecordIsForbidden() {
        loginAs(PATIENT_PHONE);
        when(staffService.findByPhone(PATIENT_PHONE)).thenReturn(null);
        when(patientService.currentPatientId()).thenReturn(null);

        ResponseEntity<?> resp = controller.list(null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(fileServiceClient);
    }

    @Test
    @DisplayName("R-62 用例D:员工不传 patientId → 允许全量列举(文件管理台路径)")
    void listAsStaffWithoutParamListsAll() {
        givenStaff();
        when(fileServiceClient.list(eq("REPORT"), isNull())).thenReturn(List.of());

        ResponseEntity<?> resp = controller.list("REPORT", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(fileServiceClient).list("REPORT", null);
    }

    // ---------- 下载 ----------

    @Test
    @DisplayName("R-62 用例E:患者下载 → 用本人 patientId 校验,并返回附件下载头")
    void downloadAsPatientUsesOwnPatientId() {
        givenPatient();
        byte[] pdf = {1, 2, 3};
        when(fileServiceClient.download("reports/1.pdf", OWN_PATIENT_ID)).thenReturn(pdf);

        ResponseEntity<?> resp = controller.download("/reports/1.pdf", OTHER_PATIENT_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(pdf);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .as("应以附件形式下载(避免浏览器内联渲染)且文件名取自对象名末段")
                .contains("attachment")
                .contains("1.pdf");
        // 关键断言:路径前导斜杠被裁掉,且用的是本人 id
        verify(fileServiceClient).download("reports/1.pdf", OWN_PATIENT_ID);
    }

    @Test
    @DisplayName("R-62 用例F:员工下载不传 patientId → 403(无法确定归属时不猜)")
    void downloadAsStaffWithoutPatientIdIsForbidden() {
        givenStaff();

        ResponseEntity<?> resp = controller.download("reports/1.pdf", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(fileServiceClient);
    }

    @Test
    @DisplayName("R-62 用例G:下游 403(归属不一致)→ 透传 403,不落成 500")
    void downloadForbiddenFromFileServiceIsForwarded() {
        givenStaff();
        when(fileServiceClient.download("reports/1.pdf", OTHER_PATIENT_ID))
                .thenThrow(new IllegalStateException("文件服务下载失败",
                        HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));

        ResponseEntity<?> resp = controller.download("reports/1.pdf", OTHER_PATIENT_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("R-62 用例H:下游不可用 → 502(而不是把下游异常暴露成 500)")
    void downloadFailureMapsToBadGateway() {
        givenStaff();
        when(fileServiceClient.download(any(), anyLong()))
                .thenThrow(new IllegalStateException("文件服务下载失败: Connection refused"));

        ResponseEntity<?> resp = controller.download("reports/1.pdf", OTHER_PATIENT_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    // ---------- 上传 ----------

    @Test
    @DisplayName("R-62 用例I:空文件上传 → 400,且不打到文件服务")
    void uploadWithEmptyFileIsBadRequest() {
        givenStaff();
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        ResponseEntity<?> resp = controller.upload(empty, null, null, null, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(fileServiceClient);
    }

    @Test
    @DisplayName("R-62 用例J:同名对象冲突(下游 409)→ 透传 409,不静默吞掉(防报告投毒)")
    void uploadConflictFromFileServiceIsForwarded() {
        givenStaff();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(fileServiceClient.upload(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("对象已存在,禁止覆盖: reports/1.pdf",
                        HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));

        ResponseEntity<?> resp = controller.upload(file, "reports/1.pdf", "REPORT", 7L, 1L, "1.pdf");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------- 测试脚手架 ----------

    /** 以指定手机号建立已认证主体(等价于 JwtAuthFilter 注入的 JwtAuthenticationToken 的 sub)。 */
    private void loginAs(String phone) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(phone, null, List.of()));
    }

    /** 当前主体是患者:staff 表查不到,且能解析到本人 patientId。 */
    private void givenPatient() {
        loginAs(PATIENT_PHONE);
        when(staffService.findByPhone(PATIENT_PHONE)).thenReturn(null);
        when(patientService.currentPatientId()).thenReturn(OWN_PATIENT_ID);
    }

    /** 当前主体是员工:staff 表按手机号命中。 */
    private void givenStaff() {
        loginAs(STAFF_PHONE);
        when(staffService.findByPhone(STAFF_PHONE)).thenReturn(mock(Staff.class));
    }
}
