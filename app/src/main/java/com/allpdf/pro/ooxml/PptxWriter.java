package com.allpdf.pro.ooxml;

import com.allpdf.pro.office.Xml;
import java.io.*;
import java.util.*;

/** Minimal valid PPTX writer: picture backgrounds + editable text boxes. Coordinates in EMU. */
public final class PptxWriter {
    public final long cx, cy;
    public String title = "Presentation";
    private final List<StringBuilder> slides = new ArrayList<>();
    private final List<List<byte[]>> slideMedia = new ArrayList<>();
    private int shapeId;

    public PptxWriter(long cxEmu, long cyEmu) {
        // PowerPoint limits: 914400 .. 51206400 EMU
        cx = Math.max(914400, Math.min(51206400, cxEmu));
        cy = Math.max(914400, Math.min(51206400, cyEmu));
    }

    public void addSlide() { slides.add(new StringBuilder()); slideMedia.add(new ArrayList<>()); shapeId = 2; }

    private StringBuilder cur() { if (slides.isEmpty()) addSlide(); return slides.get(slides.size() - 1); }

    public void picture(byte[] data, long x, long y, long w, long h) {
        StringBuilder s = cur();
        List<byte[]> m = slideMedia.get(slideMedia.size() - 1);
        m.add(data);
        String rid = "rIdImg" + m.size();
        int id = shapeId++;
        s.append("<p:pic><p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
         .append("<p:blipFill><a:blip r:embed=\"").append(rid).append("\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>")
         .append("<p:spPr><a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(w).append("\" cy=\"").append(h).append("\"/></a:xfrm>")
         .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>");
    }

    public void pictureFull(byte[] data) { picture(data, 0, 0, cx, cy); }

    public void textBox(long x, long y, long w, long h, String text, float sizePt, boolean bold, String colorHex) {
        StringBuilder s = cur();
        int id = shapeId++;
        w = Math.max(w, 12700); h = Math.max(h, 12700);
        s.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"TextBox ").append(id).append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
         .append("<p:spPr><a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(w).append("\" cy=\"").append(h).append("\"/></a:xfrm>")
         .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
         .append("<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\"><a:spAutoFit/></a:bodyPr><a:lstStyle/>");
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            s.append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"").append(Math.max(100, Math.round(sizePt * 100))).append("\"")
             .append(bold ? " b=\"1\"" : "").append(" dirty=\"0\">");
            if (colorHex != null) s.append("<a:solidFill><a:srgbClr val=\"").append(colorHex).append("\"/></a:solidFill>");
            s.append("</a:rPr><a:t>").append(Xml.esc(line)).append("</a:t></a:r></a:p>");
        }
        s.append("</p:txBody></p:sp>");
    }

    public int slideCount() { return slides.size(); }

    public void write(OutputStream os) throws IOException {
        if (slides.isEmpty()) addSlide();
        int n = slides.size();
        try (Pkg p = new Pkg(os)) {
            StringBuilder ct = new StringBuilder(Pkg.XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" + Pkg.IMG_DEFAULTS
                + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                + "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
                + "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
                + "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" + Pkg.CORE_CT);
            for (int i = 1; i <= n; i++)
                ct.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
            ct.append("</Types>");
            p.text("[Content_Types].xml", ct.toString());
            p.text("_rels/.rels", Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>");
            p.text("docProps/core.xml", Pkg.coreProps(title));

            StringBuilder pres = new StringBuilder(Pkg.XML + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" saveSubsetFonts=\"1\">"
                + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>");
            StringBuilder presRels = new StringBuilder(Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>");
            for (int i = 1; i <= n; i++) {
                pres.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rIdS").append(i).append("\"/>");
                presRels.append("<Relationship Id=\"rIdS").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide").append(i).append(".xml\"/>");
            }
            pres.append("</p:sldIdLst><p:sldSz cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
            presRels.append("</Relationships>");
            p.text("ppt/presentation.xml", pres.toString());
            p.text("ppt/_rels/presentation.xml.rels", presRels.toString());

            p.text("ppt/slideMasters/slideMaster1.xml", MASTER);
            p.text("ppt/slideMasters/_rels/slideMaster1.xml.rels", Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>");
            p.text("ppt/slideLayouts/slideLayout1.xml", LAYOUT);
            p.text("ppt/slideLayouts/_rels/slideLayout1.xml.rels", Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>");
            p.text("ppt/theme/theme1.xml", THEME);

            int mediaCounter = 0;
            for (int i = 0; i < n; i++) {
                StringBuilder rels = new StringBuilder(Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rIdL\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>");
                List<byte[]> m = slideMedia.get(i);
                for (int k = 0; k < m.size(); k++) {
                    mediaCounter++;
                    String name = "image" + mediaCounter + "." + Pkg.imageExt(m.get(k));
                    p.bytes("ppt/media/" + name, m.get(k));
                    rels.append("<Relationship Id=\"rIdImg").append(k + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/").append(name).append("\"/>");
                }
                rels.append("</Relationships>");
                p.text("ppt/slides/_rels/slide" + (i + 1) + ".xml.rels", rels.toString());
                p.text("ppt/slides/slide" + (i + 1) + ".xml", Pkg.XML + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                    + "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                    + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                    + slides.get(i) + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
            }
        }
    }

    private static final String NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"";
    private static final String EMPTY_TREE = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
        + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>";
    private static final String MASTER = Pkg.XML + "<p:sldMaster " + NS + ">"
        + "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
        + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
        + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
        + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
        + "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles>"
        + "</p:sldMaster>";
    private static final String LAYOUT = Pkg.XML + "<p:sldLayout " + NS + " type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">"
        + EMPTY_TREE.substring("<p:cSld>".length()) + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";

    private static String fill(String c) { return "<a:solidFill><a:schemeClr val=\"" + c + "\"/></a:solidFill>"; }
    private static final String THEME = Pkg.XML + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"AllPDF\"><a:themeElements>"
        + "<a:clrScheme name=\"AllPDF\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"1F2937\"/></a:dk2><a:lt2><a:srgbClr val=\"F3F4F6\"/></a:lt2>"
        + "<a:accent1><a:srgbClr val=\"E5322D\"/></a:accent1><a:accent2><a:srgbClr val=\"2563EB\"/></a:accent2><a:accent3><a:srgbClr val=\"16A34A\"/></a:accent3><a:accent4><a:srgbClr val=\"F59E0B\"/></a:accent4>"
        + "<a:accent5><a:srgbClr val=\"7C3AED\"/></a:accent5><a:accent6><a:srgbClr val=\"0891B2\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>"
        + "<a:fontScheme name=\"AllPDF\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
        + "<a:fmtScheme name=\"AllPDF\"><a:fillStyleLst>" + fill("phClr") + fill("phClr") + fill("phClr") + "</a:fillStyleLst>"
        + "<a:lnStyleLst><a:ln w=\"6350\">" + fill("phClr") + "</a:ln><a:ln w=\"12700\">" + fill("phClr") + "</a:ln><a:ln w=\"19050\">" + fill("phClr") + "</a:ln></a:lnStyleLst>"
        + "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
        + "<a:bgFillStyleLst>" + fill("phClr") + fill("phClr") + fill("phClr") + "</a:bgFillStyleLst></a:fmtScheme>"
        + "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>";
}
