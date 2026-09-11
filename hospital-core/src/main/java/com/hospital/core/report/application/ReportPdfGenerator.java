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
 * <p>中文字体(R-60):通过 {@code app.report.pdf.font-path} 注入一个含 CJK 的 TTF/OTC 路径。
 * 留空时按<b>候选列表</b>自动探测(配置值优先 → Windows → Linux → macOS 常见路径),
 * 全部不可用时降级为内置字体并告警(中文可能显示为方框),<b>不抛异常</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPdfGenerator {
    
    private final TemplateEngine templateEngine;
    private final FileServiceClient fileServiceClient;
    private final ReportMapper reportMapper;
    private final PatientService patientService;

    // R-60: 字体路径配置化(原默认写死 C:/Windows/Fonts/msyh.ttc,0 —— 在 CI/Linux 上必然缺失)。
    // 留空则按候选列表自动探测;容器/CI 建议通过 PDF_FONT_PATH 显式指定或挂载字体。
    @Value("${app.report.pdf.font-path:}")
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
            // R-60: 解析可用字体 —— 配置值优先,其次各平台候选路径;都不可用则降级(不抛异常)
            String resolvedFont = pickExistingFont();
            if (resolvedFont == null) {
                log.warn("[pdf] 未找到可用 CJK 字体(配置: '{}'),降级为内置字体,中文可能显示为方框",
                        fontPath == null ? "" : fontPath);
                // R-41: 只探测一次,后续请求跳过候选探测
                FONT_UNAVAILABLE = true;
                return null;
            }
            try {
                ITextFontResolver resolver = new ITextRenderer().getFontResolver();
                resolver.addFont(resolvedFont, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                SHARED_FONT_RESOLVER = resolver;
                log.info("[pdf] CJK 字体已注册并缓存(进程级): {}", resolvedFont);
                return resolver;
            } catch (Exception e) {
                log.warn("[pdf] 字体注册失败,回退默认字体: {}", e.getMessage());
                FONT_UNAVAILABLE = true;
                return null;
            }
        }
    }

    /**
     * R-60: 从候选列表中挑出第一个真实存在的字体路径;都不存在返回 null(由调用方降级,不抛异常)。
     * 候选顺序:配置值 → Windows → Linux → macOS。返回值保留原始字符串(可能含 ",0" 字体索引,
     * 供 {@link ITextFontResolver#addFont} 使用),仅以逗号前的文件路径部分做存在性判断。
     */
    private String pickExistingFont() {
        for (String candidate : fontCandidates(fontPath)) {
            String file = candidate.split(",")[0];
            if (new File(file).exists()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * R-60: 生成候选字体路径列表(包级可见,便于单测断言探测顺序;纯函数,不访问文件系统)。
     * <p>顺序:配置值优先,其次 Windows(雅黑/黑体/宋体)→ Linux(Noto CJK / 文泉驿 / AR PL)
     * → macOS(PingFang / 黑体)。
     */
    static java.util.List<String> fontCandidates(String configured) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        // Windows
        candidates.add("C:/Windows/Fonts/msyh.ttc,0");   // 微软雅黑
        candidates.add("C:/Windows/Fonts/simhei.ttf");   // 黑体
        candidates.add("C:/Windows/Fonts/simsun.ttc,0"); // 宋体
        // Linux(常见发行版路径;用具体路径而非 shell glob,避免依赖外连通配)
        candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0");
        candidates.add("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc");
        candidates.add("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc");
        candidates.add("/usr/share/fonts/truetype/arphic/uming.ttc");
        // macOS
        candidates.add("/System/Library/Fonts/PingFang.ttc");
        candidates.add("/System/Library/Fonts/STHeiti Medium.ttc");
        return candidates;
    }

    /**
     * R-60: 供单测直接验证 "HTML → PDF" 渲染产物,不触发模板/上传/回写整条链路。
     * 仅测试可见性提升(package-private),生产调用链(idempotent {@link #generate})保持不变。
     */
    byte[] renderPdfForTest(String html) throws Exception {
        return renderPdf(html);
    }
}
