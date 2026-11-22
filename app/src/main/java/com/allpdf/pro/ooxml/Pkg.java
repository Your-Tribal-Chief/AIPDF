package com.allpdf.pro.ooxml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

final class Pkg implements Closeable {
    private final ZipOutputStream zos;
    Pkg(OutputStream os) { zos = new ZipOutputStream(new BufferedOutputStream(os)); }
    void text(String path, String content) throws IOException { bytes(path, content.getBytes(StandardCharsets.UTF_8)); }
    void bytes(String path, byte[] b) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(b);
        zos.closeEntry();
    }
    @Override public void close() throws IOException { zos.finish(); zos.close(); }

    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    static String imageExt(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N') return "png";
        if (d.length > 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return "jpeg";
        if (d.length > 3 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "gif";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[8] == 'W' && d[9] == 'E') return "webp";
        if (d.length > 2 && d[0] == 'B' && d[1] == 'M') return "bmp";
        return "png";
    }

    static String coreProps(String title) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US) {{
            setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        }}.format(new java.util.Date());
        return XML + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + com.allpdf.pro.office.Xml.esc(title) + "</dc:title><dc:creator>AllPDF Pro</dc:creator>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified></cp:coreProperties>";
    }

    static final String CORE_CT = "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>";
    static final String IMG_DEFAULTS = "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
            + "<Default Extension=\"gif\" ContentType=\"image/gif\"/><Default Extension=\"bmp\" ContentType=\"image/bmp\"/>"
            + "<Default Extension=\"webp\" ContentType=\"image/webp\"/>";
}
