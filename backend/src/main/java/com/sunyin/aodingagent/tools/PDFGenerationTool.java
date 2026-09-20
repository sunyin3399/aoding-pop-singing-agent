package com.sunyin.aodingagent.tools;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.sunyin.aodingagent.constant.FileConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.IOException;

/**
 * PDF生成工具类
 * 用于根据给定内容生成PDF文件
 */
public class PDFGenerationTool {

    private static final Logger log = LoggerFactory.getLogger(PDFGenerationTool.class);

    /**
     * 生成PDF文件的方法
     * @param fileName 要保存的PDF文件名
     * @param content 要包含在PDF中的内容
     * @return 返回生成结果信息，成功时返回文件路径，失败时返回错误信息
     */
    @Tool(description = "生成PDF文件")
    public String generatePDF(
            @ToolParam(description = "PDF文件名") String fileName,
            @ToolParam(description = "PDF内容") String content) {
        return generatePDF(fileName, content, fileName);
    }

    public String generatePDF(String fileName, String content, String title) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) {
                return "文件名不能为空";
            }
            if (content == null || content.trim().isEmpty()) {
                return "文件内容不能为空";
            }
            
            String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
            File dir = new File(fileDir);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    return "创建文件夹失败: " + fileDir;
                }
            }
            
            if (!fileName.toLowerCase().endsWith(".pdf")) {
                fileName = fileName + ".pdf";
            }
            String filePath = fileDir + "/" + fileName;
            
            log.info("生成PDF文件到: {}", filePath);
            
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                PdfFont font = null;
                try {
                    font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                    log.info("使用中文字体: STSongStd-Light");
                } catch (Exception e) {
                    log.warn("加载中文字体失败: {}，使用默认字体", e.getMessage());
                    font = PdfFontFactory.createFont();
                }
                
                document.setFont(font);
                document.setMargins(50, 50, 50, 50);
                
                Paragraph documentTitle = new Paragraph(title == null || title.isBlank() ? fileName : title);
                documentTitle.setFont(font);
                documentTitle.setFontSize(18);
                documentTitle.setTextAlignment(TextAlignment.CENTER);
                documentTitle.setFirstLineIndent(24);
                documentTitle.setMarginBottom(20);
                document.add(documentTitle);
                
                addStyledBody(document, font, content);
            }
            
            File generatedFile = new File(filePath);
            if (generatedFile.exists() && generatedFile.length() > 0) {
                log.info("PDF生成成功: {} 字节", generatedFile.length());
                return "PDF生成成功，文件名: " + fileName + "，路径: " + filePath;
            } else {
                return "PDF文件未创建或为空";
            }
            
        } catch (IOException e) {
            log.error("PDF生成失败", e);
            return "PDF生成失败: " + e.getMessage();
        } catch (Exception e) {
            log.error("PDF生成失败，发生未知错误", e);
            return "PDF生成失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    private void addStyledBody(Document document, PdfFont font, String content) {
        for (String rawLine : content.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                document.add(new Paragraph().setMarginBottom(4));
                continue;
            }
            Paragraph paragraph = new Paragraph(line).setFont(font).setFontSize(11)
                    .setFontColor(new DeviceRgb(55, 65, 62)).setMarginBottom(5)
                    .setMultipliedLeading(1.35f);
            if (line.equals("阶段目标") || line.equals("每日训练项")
                    || line.equals("安全提示") || line.equals("阶段动作")) {
                paragraph.setFontSize(15).setFontColor(new DeviceRgb(38, 48, 45))
                        .setMarginTop(10).setMarginBottom(8);
            } else if (line.matches("^第.+阶段：.+（第.+天）$")) {
                paragraph.setFontSize(15).setFontColor(new DeviceRgb(184, 72, 41))
                        .setMarginTop(10).setMarginBottom(7);
            } else if (line.startsWith("热身：") || line.startsWith("核心训练：") || line.startsWith("放松：")) {
                paragraph.setFontSize(11).setFontColor(new DeviceRgb(184, 72, 41))
                        .setMarginTop(4).setMarginBottom(4);
            } else if (line.startsWith("停止条件：")) {
                paragraph.setFontSize(10).setFontColor(new DeviceRgb(135, 64, 52))
                        .setMarginTop(2).setMarginBottom(6);
            } else if (line.startsWith("做法：")) {
                paragraph.setMarginLeft(12).setFontSize(10.5f);
            } else if (line.startsWith("验收：")) {
                paragraph.setMarginLeft(8).setFontSize(10.5f);
            }
            document.add(paragraph);
        }
    }
}
