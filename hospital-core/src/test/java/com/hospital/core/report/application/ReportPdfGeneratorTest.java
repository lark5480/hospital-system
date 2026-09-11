package com.hospital.core.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;

import com.hospital.core.patient.application.PatientService;
import com.hospital.core.report.infrastructure.FileServiceClient;
import com.hospital.core.report.infrastructure.ReportMapper;

/**
 * R-60: {@link ReportPdfGenerator} 字体路径跨平台 + PDF 产物断言测试。
 *
 * <p>只测 {@code renderPdfForTest(html)} 这一段(HTML → PDF 字节),不触发模板渲染 / 上传 / 回写
 * report.file_id / pdf_status 的整条链路,故 reportMapper / patientService / fileServiceClient 全部用 @Mock。
 *
 * <p>关键点:本测试在<b>无中文字体</b>的环境(如精简 CI 容器)也必须通过 —— 通过反射强制
 * {@code FONT_UNAVAILABLE=true} 走"降级为内置字体"分支,断言仍能产出合法 PDF(不抛异常)。
 */
@ExtendWith(MockitoExtension.class)
class ReportPdfGeneratorTest {

    @Mock TemplateEngine templateEngine;
    @Mock FileServiceClient fileServiceClient;
    @Mock ReportMapper reportMapper;
    @Mock PatientService patientService;

    /** R-60: 保存进程级字体缓存静态字段,用例结束后恢复,避免污染同一 JVM 内的其它用例。 */
    private Boolean savedFontUnavailable;
    private Object savedFontResolver;

    @AfterEach
    void restoreStaticFontCache() {
        // FONT_UNAVAILABLE 是 static boolean 原生字段,不能写入 null;未快照(如纯逻辑用例)时跳过
        if (savedFontUnavailable != null) {
            ReflectionTestUtils.setField(ReportPdfGenerator.class, "FONT_UNAVAILABLE", savedFontUnavailable);
        }
        // SHARED_FONT_RESOLVER 是对象字段,允许置回 null
        ReflectionTestUtils.setField(ReportPdfGenerator.class, "SHARED_FONT_RESOLVER", savedFontResolver);
    }

    private void snapshotStaticFontCache() {
        savedFontUnavailable = (Boolean) ReflectionTestUtils.getField(ReportPdfGenerator.class, "FONT_UNAVAILABLE");
        savedFontResolver = ReflectionTestUtils.getField(ReportPdfGenerator.class, "SHARED_FONT_RESOLVER");
    }

    private ReportPdfGenerator newGenerator(String fontPath) {
        ReportPdfGenerator generator = new ReportPdfGenerator(
                templateEngine, fileServiceClient, reportMapper, patientService);
        ReflectionTestUtils.setField(generator, "fontPath", fontPath);
        return generator;
    }

    @Test
    @DisplayName("R-60 渲染产物:非空且以 %PDF 魔数开头(有/无中文字体环境均通过)")
    void renderPdf_producesValidPdfMagic() throws Exception {
        snapshotStaticFontCache();

        ReportPdfGenerator generator = newGenerator(null);
        String html = "<html><head><meta charset=\"UTF-8\"/></head>"
                + "<body><h1>检验报告</h1><p>结果正常 R-60</p></body></html>";

        byte[] pdf = generator.renderPdfForTest(html);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("R-60 无中文字体环境(强制降级)也生成合法 PDF,不抛异常且含 %%EOF 结束标记")
    void renderPdf_withoutCjkFont_fallsBackGracefully() throws Exception {
        snapshotStaticFontCache();
        // 强制降级:置 FONT_UNAVAILABLE=true,等价于"候选列表里一个中文字体都找不到"的环境
        ReflectionTestUtils.setField(ReportPdfGenerator.class, "FONT_UNAVAILABLE", true);

        ReportPdfGenerator generator = newGenerator("C:/__no_such_font_dir__/nope.ttf");
        byte[] pdf = generator.renderPdfForTest("<html><body><p>中文内容 abc 123</p></body></html>");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        // PDF 以 %%EOF 结束;ISO-8859-1 解码保证字节 1:1 还原,可直接 contains 断言
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("%%EOF");
    }

    @Test
    @DisplayName("R-60 候选路径:配置值优先,并覆盖 Windows/Linux/macOS 常见路径")
    void fontCandidates_configuredFirst_thenPlatformDefaults() {
        var candidates = ReportPdfGenerator.fontCandidates("/custom/font.ttf");
        assertThat(candidates).first().isEqualTo("/custom/font.ttf");
        assertThat(candidates).anyMatch(c -> c.startsWith("C:/Windows/Fonts/"));
        assertThat(candidates).anyMatch(c -> c.contains("NotoSansCJK") || c.contains("wqy-"));
        assertThat(candidates).anyMatch(c -> c.contains("PingFang"));

        // 配置为空/空白时不把空串塞进候选列表
        var withoutConfigured = ReportPdfGenerator.fontCandidates("  ");
        assertThat(withoutConfigured).noneMatch(String::isBlank);
    }
}
