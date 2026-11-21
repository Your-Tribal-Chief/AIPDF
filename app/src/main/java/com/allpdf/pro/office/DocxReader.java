package com.allpdf.pro.office;

import java.io.*;
import java.util.*;

/** Streaming DOCX parser: paragraphs, runs, headings, lists, tables (nested), inline images, page breaks. */
public final class DocxReader {
    private DocxReader() {}

    public static DocModel read(File file) throws Exception {
        ZipBag zip = new ZipBag(file);
        String docPath = "word/document.xml";
        if (!zip.has(docPath)) throw new IOException("DOCX has no word/document.xml");
        Map<String, String> rels = zip.rels(docPath);
        Map<String, Integer> styleHeading = readStyles(zip);
        DocModel model = new DocModel();
        H h = new H(zip, rels, styleHeading, model);
        h.numFmts.putAll(readNumbering(zip));
        Xml.parse(zip.open(docPath), h);
        return model;
    }

    private static Map<String, Integer> readStyles(ZipBag zip) {
        Map<String, Integer> map = new HashMap<>();
        InputStream in = zip.open("word/styles.xml");
        if (in == null) return map;
        try {
            final String[] cur = {null};
            final String[] name = {null};
            final int[] outline = {-1};
            Xml.parse(in, new Xml.Handler() {
                @Override public void start(String l, Xml.Attrs a) {
                    if (l.equals("style")) { cur[0] = a.get("styleId"); name[0] = null; outline[0] = -1; }
                    else if (cur[0] != null && l.equals("name")) name[0] = a.get("val");
                    else if (cur[0] != null && l.equals("outlineLvl")) outline[0] = a.getInt("val", -1);
                }
                @Override public void end(String l) {
                    if (l.equals("style") && cur[0] != null) {
                        int lvl = headingFrom(cur[0]);
                        if (lvl == 0 && name[0] != null) lvl = headingFrom(name[0]);
                        if (lvl == 0 && outline[0] >= 0 && outline[0] < 6) lvl = outline[0] + 1;
                        if (lvl > 0) map.put(cur[0], lvl);
                        cur[0] = null;
                    }
                }
            });
        } catch (Exception ignored) {}
        return map;
    }

    /** "numId:ilvl" -> numFmt (bullet, decimal, lowerLetter, ...). */
    private static Map<String, String> readNumbering(ZipBag zip) {
        Map<String, String> out = new HashMap<>();
        InputStream in = zip.open("word/numbering.xml");
        if (in == null) return out;
        final Map<String, Map<Integer, String>> abs = new HashMap<>();
        final Map<String, String> numToAbs = new HashMap<>();
        try {
            final String[] curAbs = {null}; final String[] curNum = {null}; final int[] lvl = {0};
            Xml.parse(in, new Xml.Handler() {
                @Override public void start(String l, Xml.Attrs a) {
                    switch (l) {
                        case "abstractNum": curAbs[0] = a.get("abstractNumId"); abs.put(curAbs[0], new HashMap<>()); break;
                        case "lvl": lvl[0] = a.getInt("ilvl", 0); break;
                        case "numFmt": if (curAbs[0] != null) abs.get(curAbs[0]).put(lvl[0], a.get("val")); break;
                        case "num": curNum[0] = a.get("numId"); break;
                        case "abstractNumId": if (curNum[0] != null) numToAbs.put(curNum[0], a.get("val")); break;
                        default: break;
                    }
                }
                @Override public void end(String l) {
                    if (l.equals("abstractNum")) curAbs[0] = null;
                    if (l.equals("num")) curNum[0] = null;
                }
            });
        } catch (Exception ignored) {}
        for (Map.Entry<String, String> e : numToAbs.entrySet()) {
            Map<Integer, String> lv = abs.get(e.getValue());
            if (lv == null) continue;
            for (Map.Entry<Integer, String> x : lv.entrySet()) out.put(e.getKey() + ":" + x.getKey(), x.getValue());
        }
        return out;
    }

    static int headingFrom(String s) {
        if (s == null) return 0;
        String t = s.toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.equals("title")) return 9;
        if (t.startsWith("heading")) {
            try { int n = Integer.parseInt(t.substring(7)); return n >= 1 && n <= 6 ? n : 0; } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private static final class H extends Xml.Handler {
        final ZipBag zip; final Map<String, String> rels; final Map<String, Integer> styles; final DocModel model;
        final Deque<DocModel.Table> tables = new ArrayDeque<>();
        final Deque<DocModel.Cell> cells = new ArrayDeque<>();
        DocModel.Para para; DocModel.Run run;
        boolean inText, inRPr, inPPr, inBody, inInstr;
        final List<DocModel.Image> pendingImages = new ArrayList<>();
        long extCx, extCy;
        final Map<String, Integer> listCounters = new HashMap<>();
        String numId; boolean numberedHint;
        final Map<String, String> numFmts = new HashMap<>();

        H(ZipBag z, Map<String, String> r, Map<String, Integer> s, DocModel m) { zip = z; rels = r; styles = s; model = m; }

        void addBlock(DocModel.Block b) {
            if (!cells.isEmpty() && b instanceof DocModel.Para) cells.peek().paras.add((DocModel.Para) b);
            else if (!cells.isEmpty() && b instanceof DocModel.Image) {
                DocModel.Para p = new DocModel.Para(); p.runs.add(new DocModel.Run("[image]")); cells.peek().paras.add(p);
            } else if (!cells.isEmpty() && b instanceof DocModel.Table) {
                // nested table: flatten as text paragraphs inside the cell
                for (List<DocModel.Cell> row : ((DocModel.Table) b).rows) {
                    StringBuilder sb = new StringBuilder();
                    for (DocModel.Cell c : row) { if (sb.length() > 0) sb.append(" | "); sb.append(c.text()); }
                    DocModel.Para p = new DocModel.Para(); p.runs.add(new DocModel.Run(sb.toString())); cells.peek().paras.add(p);
                }
            } else model.blocks.add(b);
        }

        @Override public void start(String l, Xml.Attrs a) {
            switch (l) {
                case "body": inBody = true; break;
                case "tbl": tables.push(new DocModel.Table()); break;
                case "tr": if (!tables.isEmpty()) tables.peek().rows.add(new ArrayList<>()); break;
                case "tc": {
                    DocModel.Cell c = new DocModel.Cell();
                    if (!tables.isEmpty() && !tables.peek().rows.isEmpty()) {
                        List<DocModel.Cell> row = tables.peek().rows.get(tables.peek().rows.size() - 1);
                        row.add(c);
                    }
                    cells.push(c);
                    break;
                }
                case "gridSpan": if (!cells.isEmpty()) cells.peek().gridSpan = Math.max(1, a.getInt("val", 1)); break;
                case "p": para = new DocModel.Para(); numId = null; numberedHint = false; break;
                case "pPr": inPPr = true; break;
                case "pStyle":
                    if (para != null) {
                        String id = a.get("val");
                        Integer h = styles.get(id);
                        int lvl = h != null ? h : headingFrom(id);
                        para.heading = lvl;
                        if (id != null && id.toLowerCase(Locale.ROOT).contains("list")) {
                            para.bullet = true;
                            if (id.toLowerCase(Locale.ROOT).contains("number")) numberedHint = true;
                        }
                    }
                    break;
                case "numId": if (para != null && inPPr) { numId = a.get("val"); if (!"0".equals(numId)) para.bullet = true; } break;
                case "ilvl": if (para != null && inPPr) para.indentLevel = a.getInt("val", 0); break;
                case "jc":
                    if (para != null && inPPr && !inRPr) {
                        String v = a.get("val");
                        if ("center".equals(v)) para.align = 1;
                        else if ("right".equals(v) || "end".equals(v)) para.align = 2;
                        else if ("both".equals(v) || "distribute".equals(v)) para.align = 3;
                    }
                    break;
                case "r": run = new DocModel.Run(); break;
                case "rPr": inRPr = true; break;
                case "b": if (inRPr && run != null) run.bold = !"0".equals(a.get("val")) && !"false".equals(a.get("val")); break;
                case "i": if (inRPr && run != null) run.italic = !"0".equals(a.get("val")) && !"false".equals(a.get("val")); break;
                case "u": if (inRPr && run != null) run.underline = a.get("val") != null && !"none".equals(a.get("val")); break;
                case "sz": if (inRPr && run != null) run.sizePt = a.getInt("val", 0) / 2f; break;
                case "color":
                    if (inRPr && run != null) {
                        String v = a.get("val");
                        if (v != null && v.length() == 6) try { run.color = 0xFF000000 | Integer.parseInt(v, 16); } catch (Exception ignored) {}
                    }
                    break;
                case "t": inText = true; break;
                case "instrText": inInstr = true; break;
                case "tab": if (run != null && !inPPr) run.text += "\t"; break;
                case "br":
                    if ("page".equals(a.get("type"))) {
                        flushPara(false);
                        addBlock(new DocModel.PageBreak());
                        para = new DocModel.Para();
                    } else if (run != null) run.text += "\n";
                    break;
                case "extent": extCx = a.getLong("cx", 0); extCy = a.getLong("cy", 0); break;
                case "blip": addImage(a.get("embed")); break;
                case "imagedata": addImage(a.get("id")); break;
                default: break;
            }
        }

        void addImage(String rid) {
            if (rid == null) return;
            String target = rels.get(rid);
            if (target == null) return;
            byte[] data = zip.get(target);
            if (data == null) return;
            pendingImages.add(new DocModel.Image(data, extCx / 12700f, extCy / 12700f));
            extCx = extCy = 0;
        }

        @Override public void text(char[] ch, int s, int len) {
            if (inText && run != null) run.text += new String(ch, s, len);
        }

        void flushPara(boolean end) {
            if (para == null) return;
            if (para.bullet && numId != null) {
                String fmt = numFmts.get(numId + ":" + para.indentLevel);
                if (fmt != null) numberedHint = !fmt.equals("bullet") && !fmt.equals("none");
            }
            if (para.bullet && numberedHint) {
                String key = (numId == null ? "_" : numId) + ":" + para.indentLevel;
                int n = listCounters.getOrDefault(key, 0) + 1;
                listCounters.put(key, n);
                para.numberedIndex = n;
            }
            if (!para.runs.isEmpty() || pendingImages.isEmpty()) addBlock(para);
            for (DocModel.Image im : pendingImages) addBlock(im);
            pendingImages.clear();
            para = null;
        }

        @Override public void end(String l) {
            switch (l) {
                case "t": inText = false; break;
                case "instrText": inInstr = false; break;
                case "rPr": inRPr = false; break;
                case "pPr": inPPr = false; break;
                case "r": if (para != null && run != null && !run.text.isEmpty()) para.runs.add(run); run = null; break;
                case "p": flushPara(true); break;
                case "tc": if (!cells.isEmpty()) cells.pop(); break;
                case "tbl": if (!tables.isEmpty()) { DocModel.Table t = tables.pop(); addBlock(t); } break;
                default: break;
            }
        }
    }
}
