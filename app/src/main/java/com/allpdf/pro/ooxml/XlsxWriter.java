package com.allpdf.pro.ooxml;

import com.allpdf.pro.office.SheetModel;
import com.allpdf.pro.office.Xml;
import java.io.*;
import java.util.*;

/** Minimal XLSX writer with inline strings, numeric cells and a bold header style. */
public final class XlsxWriter {
    private final List<String> names = new ArrayList<>();
    private final List<List<List<Object>>> data = new ArrayList<>();
    private final List<Boolean> header = new ArrayList<>();

    /** rows of cells; a cell is a String or a Number. */
    public void sheet(String name, List<List<Object>> rows, boolean boldFirstRow) {
        String clean = name == null ? "Sheet" : name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (clean.isEmpty()) clean = "Sheet";
        if (clean.length() > 31) clean = clean.substring(0, 31);
        String base = clean; int k = 2;
        while (containsIgnoreCase(names, clean)) { String suf = " (" + k++ + ")"; clean = base.substring(0, Math.min(base.length(), 31 - suf.length())) + suf; }
        names.add(clean); data.add(rows); header.add(boldFirstRow);
    }

    private static boolean containsIgnoreCase(List<String> l, String s) { for (String x : l) if (x.equalsIgnoreCase(s)) return true; return false; }

    public static Object autoType(String s) {
        if (s == null) return "";
        String t = s.trim().replace(",", "");
        if (t.matches("-?\\d{1,15}(\\.\\d+)?")) {
            try { return Double.parseDouble(t); } catch (Exception ignored) {}
        }
        return s;
    }

    public void write(OutputStream os) throws IOException {
        if (names.isEmpty()) sheet("Sheet1", new ArrayList<>(), false);
        try (Pkg p = new Pkg(os)) {
            StringBuilder ct = new StringBuilder(Pkg.XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" + Pkg.CORE_CT);
            for (int i = 1; i <= names.size(); i++)
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            ct.append("</Types>");
            p.text("[Content_Types].xml", ct.toString());
            p.text("_rels/.rels", Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>");
            p.text("docProps/core.xml", Pkg.coreProps("Workbook"));
            StringBuilder wb = new StringBuilder(Pkg.XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder wbRels = new StringBuilder(Pkg.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            for (int i = 0; i < names.size(); i++) {
                wb.append("<sheet name=\"").append(Xml.esc(names.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
                wbRels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
            }
            wb.append("</sheets></workbook>");
            wbRels.append("</Relationships>");
            p.text("xl/workbook.xml", wb.toString());
            p.text("xl/_rels/workbook.xml.rels", wbRels.toString());
            p.text("xl/styles.xml", Pkg.XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE5E7EB\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            for (int i = 0; i < names.size(); i++) p.text("xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(data.get(i), header.get(i)));
        }
    }

    private static String sheetXml(List<List<Object>> rows, boolean head) {
        int maxCols = 0;
        for (List<Object> r : rows) maxCols = Math.max(maxCols, r.size());
        int[] widths = new int[maxCols];
        for (List<Object> r : rows) for (int c = 0; c < r.size(); c++) {
            Object v = r.get(c);
            int len = v == null ? 0 : (v instanceof Number ? SheetModel.formatNumber(((Number) v).doubleValue()) : v.toString()).length();
            widths[c] = Math.max(widths[c], Math.min(60, len));
        }
        StringBuilder s = new StringBuilder(Pkg.XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (maxCols > 0) {
            s.append("<cols>");
            for (int c = 0; c < maxCols; c++) s.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(Math.max(8, widths[c] + 2)).append("\" customWidth=\"1\"/>");
            s.append("</cols>");
        }
        s.append("<sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            s.append("<row r=\"").append(r + 1).append("\">");
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                if (v == null) continue;
                String ref = SheetModel.colName(c) + (r + 1);
                String style = head && r == 0 ? " s=\"1\"" : "";
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    s.append("<c r=\"").append(ref).append("\"").append(style).append("><v>").append(SheetModel.formatNumber(d)).append("</v></c>");
                } else {
                    String t = v.toString();
                    if (t.isEmpty()) continue;
                    if (t.length() > 32767) t = t.substring(0, 32767);
                    s.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style).append("><is><t xml:space=\"preserve\">").append(Xml.esc(t)).append("</t></is></c>");
                }
            }
            s.append("</row>");
        }
        s.append("</sheetData></worksheet>");
        return s.toString();
    }
}
