package com.allpdf.pro.office;

import java.io.*;
import java.util.*;

public final class XlsxReader {
    private XlsxReader() {}

    public static SheetModel read(File file) throws Exception {
        ZipBag zip = new ZipBag(file);
        String wb = "xl/workbook.xml";
        if (!zip.has(wb)) throw new IOException("XLSX has no xl/workbook.xml");
        Map<String, String> rels = zip.rels(wb);
        final List<String[]> sheets = new ArrayList<>(); // name, rid
        final boolean[] date1904 = {false};
        Xml.parse(zip.open(wb), new Xml.Handler() {
            @Override public void start(String l, Xml.Attrs a) {
                if (l.equals("sheet")) {
                    String rid = a.q("r:id"); if (rid == null) rid = a.get("id");
                    String state = a.get("state");
                    if (!"hidden".equals(state) && !"veryHidden".equals(state)) sheets.add(new String[]{a.get("name"), rid});
                } else if (l.equals("workbookPr")) {
                    String v = a.get("date1904"); date1904[0] = "1".equals(v) || "true".equals(v);
                }
            }
        });
        List<String> shared = readShared(zip);
        boolean[][] styleInfo = readStyles(zip); // [0]=isDate per xf, [1]=bold per xf
        SheetModel model = new SheetModel();
        for (String[] s : sheets) {
            String path = rels.get(s[1]);
            if (path == null || !zip.has(path)) continue;
            SheetModel.Sheet sheet = new SheetModel.Sheet();
            sheet.name = s[0] == null ? "Sheet" : s[0];
            parseSheet(zip.open(path), sheet, shared, styleInfo, date1904[0]);
            model.sheets.add(sheet);
        }
        return model;
    }

    static List<String> readShared(ZipBag zip) throws Exception {
        List<String> out = new ArrayList<>();
        InputStream in = zip.open("xl/sharedStrings.xml");
        if (in == null) return out;
        Xml.parse(in, new Xml.Handler() {
            StringBuilder cur; boolean inT, inRph;
            @Override public void start(String l, Xml.Attrs a) {
                if (l.equals("si")) cur = new StringBuilder();
                else if (l.equals("t")) inT = true;
                else if (l.equals("rPh")) inRph = true;
            }
            @Override public void text(char[] ch, int s, int len) { if (inT && !inRph && cur != null) cur.append(ch, s, len); }
            @Override public void end(String l) {
                if (l.equals("t")) inT = false;
                else if (l.equals("rPh")) inRph = false;
                else if (l.equals("si")) { out.add(cur.toString()); cur = null; }
            }
        });
        return out;
    }

    static boolean isDateFormat(int id, String code) {
        if ((id >= 14 && id <= 22) || (id >= 45 && id <= 47)) return true;
        if (code == null) return false;
        String c = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").toLowerCase(Locale.ROOT);
        return c.contains("yy") || c.contains("dd") || (c.contains("m") && c.contains("d")) || c.contains("hh");
    }

    static boolean[][] readStyles(ZipBag zip) {
        InputStream in = zip.open("xl/styles.xml");
        if (in == null) return new boolean[][]{new boolean[0], new boolean[0]};
        final Map<Integer, String> fmts = new HashMap<>();
        final List<Boolean> boldFonts = new ArrayList<>();
        final List<int[]> xfs = new ArrayList<>(); // numFmtId, fontId
        try {
            Xml.parse(in, new Xml.Handler() {
                boolean inCellXfs, inFonts, inFont;
                @Override public void start(String l, Xml.Attrs a) {
                    switch (l) {
                        case "numFmt": fmts.put(a.getInt("numFmtId", -1), a.get("formatCode")); break;
                        case "fonts": inFonts = true; break;
                        case "font": if (inFonts) { inFont = true; boldFonts.add(false); } break;
                        case "b": if (inFont && !boldFonts.isEmpty()) boldFonts.set(boldFonts.size() - 1, !"0".equals(a.get("val"))); break;
                        case "cellXfs": inCellXfs = true; break;
                        case "xf": if (inCellXfs) xfs.add(new int[]{a.getInt("numFmtId", 0), a.getInt("fontId", 0)}); break;
                        default: break;
                    }
                }
                @Override public void end(String l) {
                    if (l.equals("cellXfs")) inCellXfs = false;
                    else if (l.equals("fonts")) inFonts = false;
                    else if (l.equals("font")) inFont = false;
                }
            });
        } catch (Exception ignored) {}
        boolean[] date = new boolean[xfs.size()], bold = new boolean[xfs.size()];
        for (int i = 0; i < xfs.size(); i++) {
            int id = xfs.get(i)[0];
            date[i] = isDateFormat(id, fmts.get(id));
            int f = xfs.get(i)[1];
            bold[i] = f >= 0 && f < boldFonts.size() && boldFonts.get(f);
        }
        return new boolean[][]{date, bold};
    }

    static void parseSheet(InputStream in, SheetModel.Sheet sheet, List<String> shared, boolean[][] styles, boolean d1904) throws Exception {
        Xml.parse(in, new Xml.Handler() {
            String ref, type; int style; StringBuilder val; boolean inV, inIsT;
            int autoRow = -1, autoCol = -1;
            @Override public void start(String l, Xml.Attrs a) {
                switch (l) {
                    case "col": {
                        int min = a.getInt("min", 0), max = a.getInt("max", 0);
                        String w = a.get("width");
                        if (w != null && max - min < 200) {
                            try { float fw = Float.parseFloat(w); for (int c = min; c <= max; c++) sheet.colWidthChars.put(c - 1, fw); } catch (Exception ignored) {}
                        }
                        break;
                    }
                    case "row": autoRow = a.getInt("r", autoRow + 2) - 1; autoCol = -1; break;
                    case "c": ref = a.get("r"); type = a.get("t"); style = a.getInt("s", 0); val = new StringBuilder(); break;
                    case "v": inV = true; break;
                    case "t": inIsT = "inlineStr".equals(type); break;
                    default: break;
                }
            }
            @Override public void text(char[] ch, int s, int len) { if ((inV || inIsT) && val != null) val.append(ch, s, len); }
            @Override public void end(String l) {
                if (l.equals("v")) inV = false;
                else if (l.equals("t")) inIsT = false;
                else if (l.equals("c")) {
                    int r, c;
                    if (ref != null) { int[] rc = SheetModel.ref(ref); r = rc[0]; c = rc[1]; }
                    else { r = Math.max(autoRow, 0); c = autoCol + 1; }
                    autoCol = c;
                    String raw = val == null ? "" : val.toString();
                    String out = raw;
                    boolean num = false;
                    if ("s".equals(type)) {
                        try { int idx = Integer.parseInt(raw.trim()); out = idx < shared.size() ? shared.get(idx) : ""; } catch (Exception e) { out = ""; }
                    } else if ("b".equals(type)) {
                        out = "1".equals(raw.trim()) ? "TRUE" : "FALSE";
                    } else if ("e".equals(type) || "str".equals(type) || "inlineStr".equals(type)) {
                        out = raw;
                    } else if (!raw.isEmpty()) {
                        try {
                            double d = Double.parseDouble(raw.trim());
                            boolean isDate = style < styles[0].length && styles[0][style];
                            if (isDate) out = SheetModel.excelDate(d1904 ? d + 1462 : d);
                            else { out = SheetModel.formatNumber(d); num = true; }
                        } catch (Exception e) { out = raw; }
                    }
                    sheet.put(r, c, out);
                    if (num) sheet.numeric.add(r + ":" + c);
                    if (style < styles[1].length && styles[1][style]) sheet.bold.add(r + ":" + c);
                    val = null;
                }
            }
        });
    }
}
