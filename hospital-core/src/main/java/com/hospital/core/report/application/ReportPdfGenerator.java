package com.hospital.core.report.application;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import com.hospital.core.patient.application.PatientService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.infrastructure.FileServiceClient;
import com.hospital.core.report.infrastructure.ReportMapper;
import com.lowagie.text.pdf.BaseFont;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 报告 PDF 生成器:Markdown 内容 → HTML(CommonMark) → PDF(Flying Saucer),
 * 再经 FileServiceClient 上传到 MinIO,并回写 report.file_id / pdf_status。
 *
 * <p>中文字体:通过 {@code report.pdf.font-path} 注入一个含 CJK 的 TTF/OTC 路径,
 * 默认取本机微软雅黑。缺失时仅告警,PDF 仍生成(中文可能显示为方框)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPdfGenerator {
    
    private final TemplateEngine templateEngine;
    private final FileServiceClient fileServiceClient;
    private final ReportMapper reportMapper;
    private final PatientService patientService;

    @Value("${report.pdf.font-path:C:/Windows/Fonts/msyh.ttc,0}")
    private String fontPath;

    private final Parser mdParser = Parser.builder().build();
    private final HtmlRenderer mdRenderer = HtmlRenderer.builder().build();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 生成并上传指定报告的 PDF;幂等(reports/{reportId}.pdf 覆盖写)。 */
    public void generate(Long reportId, Long patientId, String title, String content) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            log.warn("[pdf] 报告不存在,跳过生成: {}", reportId);
            return;
        }
        try {
            String patientName = patientService.getName(patientId);
            String contentHtml = (content == null || content.isBlank())
                    ? "<p>无内容</p>"
                    : mdRenderer.render(mdParser.parse(content));

            Context ctx = new Context();
            ctx.setVariable("title", title);
            ctx.setVariable("patientName", patientName != null ? patientName : "—");
            ctx.setVariable("reportId", reportId);
            ctx.setVariable("generatedAt", LocalDateTime.now().format(FMT));
            ctx.setVariable("contentHtml", contentHtml);
            String html = templateEngine.process("report", ctx);

            byte[] pdf = renderPdf(html);

            String objectName = "reports/" + reportId + ".pdf";
            String stored = fileServiceClient.uploadPdf(
                    pdf, patientId, reportId, objectName, objectName);
            report.setFileId(stored);
            report.setPdfStatus("READY");
            reportMapper.updateById(report);
            log.info("[pdf] 报告 {} PDF 已生成并上传: {}", reportId, stored);
        } catch (Exception e) {
            log.error("[pdf] 报告 {} PDF 生成失败: {}", reportId, e.getMessage(), e);
            report.setPdfStatus("FAILED");
            reportMapper.updateById(report);
        }
    }

    private byte[] renderPdf(String html) throws Exception {
        ITextRenderer renderer = new ITextRenderer();
        ITextFontResolver fontResolver = renderer.getFontResolver();
        if (fontPath != null && !fontPath.isBlank()) {
            String base = fontPath.split(",")[0];
            if (new File(base).exists()) {
                try {
                    fontResolver.addFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } catch (Exception e) {
                    log.warn("[pdf] 字体注册失败,回退默认字体: {}", e.getMessage());
                }
            } else {
                log.warn("[pdf] 字体文件不存在,中文可能显示为方框: {}", base);
            }
        }
        renderer.setDocumentFromString(html);
        renderer.layout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        renderer.createPDF(os);
        return os.toByteArray();
    }
}
