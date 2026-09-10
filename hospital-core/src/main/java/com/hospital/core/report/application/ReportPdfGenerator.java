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

    // R-41: 进程级静态缓存。CJK 字体(约 20MB msyh.ttc)解析 + 注册是重活,
    // 原实现每次生成都在 renderPdf 里 new File(...).exists() + fontResolver.addFont(...),
    // 等于每次重新解析并嵌入一次字体;现将结果缓存到进程级,首解析后置标记,后续复用。
    /** R-41: 进程级共享字体解析器(已注册字体);null 表示尚未初始化。 */
    private static volatile ITextFontResolver SHARED_FONT_RESOLVER;
    /** R-41: 字体缺失/注册失败的降级标记,置位后跳过 exists() 探测与 addFont。 */
    private static volatile boolean FONT_UNAVAILABLE;
    /** R-41: 共享解析器惰性初始化的锁。 */
    private static final Object FONT_LOCK = new Object();

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
        // R-41: 复用进程级共享字体解析器(字体已注册一次),避免每次 new ITextRenderer 后重新解析 20MB 字体;
        // 字体缺失时 sharedFontResolver() 返回 null,走默认字体(保留原降级逻辑:中文可能显示为方框)。
        ITextFontResolver resolver = sharedFontResolver();
        if (resolver != null) {
            renderer.getSharedContext().setFontResolver(resolver);
        }
        renderer.setDocumentFromString(html);
        renderer.layout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        renderer.createPDF(os);
        // TODO(P2 R-41): 下载路径当前在 HTTP 请求线程内同步生成 PDF(属接口语义),本轮不改动,
        // 应改为异步生成 + 轮询 report.pdfStatus,避免长任务占用请求线程。
        return os.toByteArray();
    }

    /**
     * R-41: 惰性构建进程级共享字体解析器。首解析后置标记,后续请求直接复用,
     * 跳过 {@code exists()} 探测与重复 {@code addFont}(避免重复解析嵌入约 20MB 字体)。
     * 字体缺失或注册失败时返回 null,保持原有"降级为默认字体"逻辑。
     *
     * <p>说明:ITextFontResolver 的 resolveFont 使用传入的 SharedContext(非构造时字段),
     * 且 BaseFont 可跨文档复用,故可安全注入到各次新建的 ITextRenderer。
     */
    private ITextFontResolver sharedFontResolver() {
        if (FONT_UNAVAILABLE) {
            return null;
        }
        ITextFontResolver cached = SHARED_FONT_RESOLVER;
        if (cached != null) {
            return cached;
        }
        synchronized (FONT_LOCK) {
            if (FONT_UNAVAILABLE) {
                return null;
            }
            if (SHARED_FONT_RESOLVER != null) {
                return SHARED_FONT_RESOLVER;
            }
            if (fontPath == null || fontPath.isBlank()) {
                FONT_UNAVAILABLE = true;
                return null;
            }
            String base = fontPath.split(",")[0];
            if (!new File(base).exists()) {
                log.warn("[pdf] 字体文件不存在,中文可能显示为方框: {}", base);
                // R-41: 只探测一次,后续请求跳过 exists()
                FONT_UNAVAILABLE = true;
                return null;
            }
            try {
                ITextFontResolver resolver = new ITextRenderer().getFontResolver();
                resolver.addFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                SHARED_FONT_RESOLVER = resolver;
                log.info("[pdf] CJK 字体已注册并缓存(进程级): {}", fontPath);
                return resolver;
            } catch (Exception e) {
                log.warn("[pdf] 字体注册失败,回退默认字体: {}", e.getMessage());
                FONT_UNAVAILABLE = true;
                return null;
            }
        }
    }
}
