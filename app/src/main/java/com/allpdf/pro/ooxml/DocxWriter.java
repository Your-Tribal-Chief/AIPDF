package com.allpdf.pro.ooxml;

import com.allpdf.pro.office.Xml;
import java.io.*;
import java.util.*;

/** Minimal, standards-valid DOCX writer (headings, styled runs, bullets, tables, images, page breaks). */
public final class DocxWriter {
    private final StringBuilder body = new StringBuilder();
    private final List<byte[]> media = new ArrayList<>();
    private final List<String> mediaExt = new ArrayList<>();
    private int docPrId = 1;
    public String title = "Document";
    /** Usable content width in points (Letter with 1" margins = 468pt). */
    public float contentWidthPt = 468f;

    public static final class Run {
        public String text; public boolean bold, italic; public float sizePt; public String colorHex;
        public Run(String t) { text = t; }
    }

    public void heading(String text, int level) {
        level = Math.max(1, Math.min(level, 3));
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading").append(level).append("\"/></w:pPr>");
        runXml(new Run(text));
        body.append("</w:p>");
    }

    public void paragraph(String text) { paragraph(Collections.singletonList(new Run(text)), 0); }

    /** align: 0 left, 1 center, 2 right, 3 justify */
    public void paragraph(List<Run> runs, int align) {
        body.append("<w:p>");
        if (align != 0) body.append("<w:pPr><w:jc w:val=\"").append(align == 1 ? "center" : align == 2 ? "right" : "both").append("\"/></w:pPr>");
        for (Run r : runs) runXml(r);
        body.append("</w:p>");
    }

    public void bullet(String text) {
        body.append("<w:p><w:pPr><w:pStyle w:val=\"ListBullet\"/><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>");
        runXml(new Run("•\t" + text));
        body.append("</w:p>");
    }

    private void runXml(Run r) {
        body.append("<w:r>");
        if (r.bold || r.italic || r.sizePt > 0 || r.colorHex != null) {
            body.append("<w:rPr>");
            if (r.bold) body.append("<w:b/>");
            if (r.italic) body.append("<w:i/>");
            if (r.colorHex != null) body.append("<w:color w:val=\"").append(r.colorHex).append("\"/>");
            if (r.sizePt > 0) body.append("<w:sz w:val=\"").append(Math.round(r.sizePt * 2)).append("\"/>");
            body.append("</w:rPr>");
        }
        String t = r.text == null ? "" : r.text;
        String[] lines = t.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) body.append("<w:br/>");
            String[] tabs = lines[i].split("\t", -1);
            for (int j = 0; j < tabs.length; j++) {
                if (j > 0) body.append("<w:tab/>");
                body.append("<w:t xml:space=\"preserve\">").append(Xml.esc(tabs[j])).append("</w:t>");
            }
        }
        body.append("</w:r>");
    }

    public void pageBreak() { body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"); }

    /** Image sized in points; scaled down to fit the content width. */
    public void image(byte[] data, float wPt, float hPt) {
        if (data == null || data.length == 0 || wPt <= 0 || hPt <= 0) return;
        if (wPt > contentWidthPt) { hPt = hPt * contentWidthPt / wPt; wPt = contentWidthPt; }
        if (hPt > 648) { wPt = wPt * 648 / hPt; hPt = 648; }
        media.add(data);
        mediaExt.add(Pkg.imageExt(data));
        int idx = media.size();
        String rid = "rIdImg" + idx;
        long cx = Math.round(wPt * 12700), cy = Math.round(hPt * 12700);
        int id = docPrId++;
        body.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
            .append("<wp:docPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            .append("<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"image").append(idx).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"").append(rid).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
    }

    public void table(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return;
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        if (cols == 0) return;
        int colW = (int) (contentWidthPt * 20 / cols);
        body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
            .append("<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/><w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>")
            .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/><w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>")
            .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/></w:tblBorders>")
            .append("</w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) body.append("<w:gridCol w:w=\"").append(colW).append("\"/>");
        body.append("</w:tblGrid>");
        for (List<String> r : rows) {
            body.append("<w:tr>");
            for (int c = 0; c < cols; c++) {
                String v = c < r.size() && r.get(c) != null ? r.get(c) : "";
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(colW).append("\" w:type=\"dxa\"/></w:tcPr><w:p>");
                runXml(new Run(v));
                body.append("</w:p></w:tc>");
            }
            body.append("</w:tr>");
        }
        body.append("</w:tbl><w:p/>");
    }

    public void write(OutputStream os) throws IOException {
        try (Pkg p = new Pkg(os)) {
            p.text("[Content_Types].xml", Pkg.XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" + Pkg.IMG_DEFAULTS
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + Pkg.CORE_CT + "</Types>");
            p.text("_rels/.rels", Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>");
            p.text("docProps/core.xml", Pkg.coreProps(title));
            StringBuilder rels = new StringBuilder(Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            for (int i = 0; i < media.size(); i++) {
                String name = "image" + (i + 1) + "." + mediaExt.get(i);
                rels.append("<Relationship Id=\"rIdImg").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/").append(name).append("\"/>");
                p.bytes("word/media/" + name, media.get(i));
            }
            rels.append("</Relationships>");
            p.text("word/_rels/document.xml.rels", rels.toString());
            p.text("word/styles.xml", STYLES);
            p.text("word/document.xml", Pkg.XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>"
                + body + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>");
        }
    }

    private static final String STYLES = Pkg.XML + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"36\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"2F5496\"/><w:sz w:val=\"30\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/><w:basedOn w:val=\"Normal\"/></w:style>"
        + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
        + "</w:styles>";
}
