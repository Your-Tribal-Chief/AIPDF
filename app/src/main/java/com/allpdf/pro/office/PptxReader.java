package com.allpdf.pro.office;

import java.io.*;
import java.util.*;

/** PPTX parser producing positioned shapes per slide, with layout/master placeholder inheritance. */
public final class PptxReader {
    private PptxReader() {}

    static final class Part {
        final List<SlideModel.Shape> shapes = new ArrayList<>();
        int bg; byte[] bgImage;
        boolean showMasterShapes = true;
    }

    public static SlideModel read(File file) throws Exception {
        ZipBag zip = new ZipBag(file);
        String pres = "ppt/presentation.xml";
        if (!zip.has(pres)) throw new IOException("PPTX has no ppt/presentation.xml");
        SlideModel model = new SlideModel();
        final List<String> order = new ArrayList<>();
        Xml.parse(zip.open(pres), new Xml.Handler() {
            @Override public void start(String l, Xml.Attrs a) {
                if (l.equals("sldSz")) {
                    model.widthEmu = a.getLong("cx", model.widthEmu);
                    model.heightEmu = a.getLong("cy", model.heightEmu);
                } else if (l.equals("sldId")) {
                    String rid = a.q("r:id");
                    if (rid == null) rid = a.get("id");
                    order.add(rid);
                }
            }
        });
        Map<String, String> presRels = zip.rels(pres);
        Map<String, Part> cache = new HashMap<>();
        for (String rid : order) {
            String slidePath = presRels.get(rid);
            if (slidePath == null || !zip.has(slidePath)) continue;
            Part slide = parsePart(zip, slidePath);
            SlideModel.Slide out = new SlideModel.Slide();

            // Resolve layout and master.
            String layoutPath = findRelByType(zip, slidePath, "slideLayout");
            Part layout = layoutPath == null ? null : cache.computeIfAbsent(layoutPath, p -> safeParse(zip, p));
            String masterPath = layoutPath == null ? null : findRelByType(zip, layoutPath, "slideMaster");
            Part master = masterPath == null ? null : cache.computeIfAbsent(masterPath, p -> safeParse(zip, p));

            out.background = slide.bg != 0 ? slide.bg : layout != null && layout.bg != 0 ? layout.bg : master != null ? master.bg : 0;
            out.backgroundImage = slide.bgImage != null ? slide.bgImage : layout != null && layout.bgImage != null ? layout.bgImage
                    : master != null ? master.bgImage : null;

            if (slide.showMasterShapes) {
                if (master != null) for (SlideModel.Shape s : master.shapes) if (s.phType == null && s.phIdx == null && s.hasXfrm && (s.image != null || s.fill != 0)) out.shapes.add(s);
                if (layout != null) for (SlideModel.Shape s : layout.shapes) if (s.phType == null && s.phIdx == null && s.hasXfrm && (s.image != null || s.fill != 0)) out.shapes.add(s);
            }
            for (SlideModel.Shape s : slide.shapes) {
                if (!s.hasXfrm && (s.phType != null || s.phIdx != null)) {
                    SlideModel.Shape src = matchPlaceholder(layout, s);
                    if (src == null || !src.hasXfrm) src = matchPlaceholder(master, s);
                    if (src != null && src.hasXfrm) { s.x = src.x; s.y = src.y; s.w = src.w; s.h = src.h; s.hasXfrm = true; }
                }
                if (!s.hasXfrm) {
                    // Last resort placement.
                    boolean title = isTitle(s.phType);
                    s.x = model.widthEmu / 12; s.w = model.widthEmu * 10 / 12;
                    s.y = title ? model.heightEmu / 14 : model.heightEmu / 4;
                    s.h = title ? model.heightEmu / 6 : model.heightEmu * 2 / 3;
                    s.hasXfrm = true;
                }
                if (s.defaultSize == 0) s.defaultSize = isTitle(s.phType) ? 36f : (s.phType != null || s.phIdx != null) ? 22f : 18f;
                out.shapes.add(s);
            }
            // speaker notes
            String notesPath = findRelByType(zip, slidePath, "notesSlide");
            if (notesPath != null) {
                Part np = safeParse(zip, notesPath);
                StringBuilder sb = new StringBuilder();
                for (SlideModel.Shape s : np.shapes) if ("body".equals(s.phType)) for (DocModel.Para p : s.paras) sb.append(p.text()).append('\n');
                out.notes = sb.toString().trim();
            }
            model.slides.add(out);
        }
        return model;
    }

    static boolean isTitle(String t) { return "title".equals(t) || "ctrTitle".equals(t); }

    static SlideModel.Shape matchPlaceholder(Part part, SlideModel.Shape s) {
        if (part == null) return null;
        if (s.phIdx != null) for (SlideModel.Shape c : part.shapes) if (s.phIdx.equals(c.phIdx)) return c;
        String t = s.phType == null ? "body" : s.phType;
        for (SlideModel.Shape c : part.shapes) {
            String ct = c.phType == null ? (c.phIdx != null ? "body" : null) : c.phType;
            if (ct == null) continue;
            if (ct.equals(t) || (isTitle(t) && isTitle(ct))) return c;
        }
        return null;
    }

    static String findRelByType(ZipBag zip, String part, String typeSuffix) {
        Map<String, String> types = zip.relTypes(part);
        Map<String, String> targets = zip.rels(part);
        for (Map.Entry<String, String> e : types.entrySet())
            if (e.getValue().endsWith("/" + typeSuffix)) return targets.get(e.getKey());
        return null;
    }

    static Part safeParse(ZipBag zip, String path) {
        try { return parsePart(zip, path); } catch (Exception e) { return new Part(); }
    }

    static int parseHex(String v) {
        if (v == null || v.length() != 6) return 0;
        try { return 0xFF000000 | Integer.parseInt(v, 16); } catch (Exception e) { return 0; }
    }

    static Part parsePart(ZipBag zip, String path) throws Exception {
        Part part = new Part();
        Map<String, String> rels = zip.rels(path);
        Xml.parse(zip.open(path), new Xml.Handler() {
            // group transform stack: {offX, offY, extX, extY, chOffX, chOffY, chExtX, chExtY}
            final Deque<long[]> groups = new ArrayDeque<>();
            SlideModel.Shape shape; DocModel.Para para; DocModel.Run run;
            boolean inSpPr, inLn, inRPr, inT, inBg, inGrpSpPr, inXfrm, inTable, inTc, inBodyPr;
            long[] grpPending; // collecting grpSpPr
            List<String> tableRow; StringBuilder tcText;

            long[] mapPt(long x, long y) {
                for (Iterator<long[]> it = groups.iterator(); it.hasNext(); ) {
                    long[] g = it.next();
                    double sx = g[6] == 0 ? 1 : (double) g[2] / g[6];
                    double sy = g[7] == 0 ? 1 : (double) g[3] / g[7];
                    x = g[0] + Math.round((x - g[4]) * sx);
                    y = g[1] + Math.round((y - g[5]) * sy);
                }
                return new long[]{x, y};
            }
            long[] mapExt(long w, long h) {
                for (long[] g : groups) {
                    double sx = g[6] == 0 ? 1 : (double) g[2] / g[6];
                    double sy = g[7] == 0 ? 1 : (double) g[3] / g[7];
                    w = Math.round(w * sx); h = Math.round(h * sy);
                }
                return new long[]{w, h};
            }

            @Override public void start(String l, Xml.Attrs a) {
                switch (l) {
                    case "sld": if ("0".equals(a.get("showMasterSp"))) part.showMasterShapes = false; break;
                    case "bg": inBg = true; break;
                    case "grpSp": grpPending = new long[8]; break;
                    case "grpSpPr": inGrpSpPr = true; break;
                    case "sp": case "pic": case "cxnSp": case "graphicFrame":
                        shape = new SlideModel.Shape();
                        break;
                    case "ph": if (shape != null) { shape.phType = a.get("type"); shape.phIdx = a.get("idx"); } break;
                    case "spPr": inSpPr = true; break;
                    case "xfrm":
                        inXfrm = true;
                        if (shape != null) shape.rotation = a.getInt("rot", 0) / 60000;
                        break;
                    case "off":
                        if (inGrpSpPr && grpPending != null) { grpPending[0] = a.getLong("x", 0); grpPending[1] = a.getLong("y", 0); }
                        else if (shape != null && inXfrm) {
                            long[] p = mapPt(a.getLong("x", 0), a.getLong("y", 0));
                            shape.x = p[0]; shape.y = p[1]; shape.hasXfrm = true;
                        }
                        break;
                    case "ext":
                        if (inGrpSpPr && grpPending != null) { grpPending[2] = a.getLong("cx", 0); grpPending[3] = a.getLong("cy", 0); }
                        else if (shape != null && inXfrm) {
                            long[] e = mapExt(a.getLong("cx", 0), a.getLong("cy", 0));
                            shape.w = e[0]; shape.h = e[1];
                        }
                        break;
                    case "chOff": if (inGrpSpPr && grpPending != null) { grpPending[4] = a.getLong("x", 0); grpPending[5] = a.getLong("y", 0); } break;
                    case "chExt": if (inGrpSpPr && grpPending != null) { grpPending[6] = a.getLong("cx", 0); grpPending[7] = a.getLong("cy", 0); } break;
                    case "prstGeom": if (shape != null) shape.geom = a.get("prst"); break;
                    case "ln": inLn = true; break;
                    case "srgbClr": {
                        int c = parseHex(a.get("val"));
                        if (inBg) part.bg = c;
                        else if (inRPr && run != null) run.color = c;
                        else if (inLn && shape != null) shape.line = c;
                        else if (inSpPr && shape != null) shape.fill = c;
                        break;
                    }
                    case "blip": {
                        String rid = a.q("r:embed"); if (rid == null) rid = a.get("embed");
                        String target = rid == null ? null : rels.get(rid);
                        byte[] img = target == null ? null : zip.get(target);
                        if (inBg) part.bgImage = img;
                        else if (shape != null && img != null) shape.image = img;
                        break;
                    }
                    case "bodyPr": {
                        inBodyPr = true;
                        if (shape != null) {
                            String an = a.get("anchor");
                            shape.anchor = "ctr".equals(an) ? 1 : "b".equals(an) ? 2 : 0;
                        }
                        break;
                    }
                    case "tbl": inTable = true; if (shape != null) shape.table = new ArrayList<>(); break;
                    case "tr": if (inTable) tableRow = new ArrayList<>(); break;
                    case "tc": if (inTable) { inTc = true; tcText = new StringBuilder(); } break;
                    case "p": if (shape != null && !inTc) para = new DocModel.Para(); break;
                    case "pPr":
                        if (para != null) {
                            String al = a.get("algn");
                            para.align = "ctr".equals(al) ? 1 : "r".equals(al) ? 2 : "just".equals(al) ? 3 : 0;
                            para.indentLevel = a.getInt("lvl", 0);
                        }
                        break;
                    case "buChar": case "buAutoNum": if (para != null) para.bullet = true; break;
                    case "r": case "fld": if (para != null) run = new DocModel.Run(); break;
                    case "br": if (para != null) { DocModel.Run b = new DocModel.Run("\n"); para.runs.add(b); } break;
                    case "rPr": case "endParaRPr": case "defRPr":
                        inRPr = l.equals("rPr");
                        if (run != null && l.equals("rPr")) {
                            int sz = a.getInt("sz", 0);
                            if (sz > 0) run.sizePt = sz / 100f;
                            String b = a.get("b"); run.bold = "1".equals(b) || "true".equals(b);
                            String i = a.get("i"); run.italic = "1".equals(i) || "true".equals(i);
                            String u = a.get("u"); run.underline = u != null && !"none".equals(u);
                        }
                        break;
                    case "t": inT = true; break;
                    default: break;
                }
            }

            @Override public void text(char[] ch, int s, int len) {
                if (!inT) return;
                String t = new String(ch, s, len);
                if (inTc && tcText != null) tcText.append(t);
                else if (run != null) run.text += t;
            }

            @Override public void end(String l) {
                switch (l) {
                    case "bg": inBg = false; break;
                    case "grpSpPr":
                        inGrpSpPr = false;
                        if (grpPending != null) { groups.push(grpPending); grpPending = null; }
                        break;
                    case "grpSp": if (!groups.isEmpty()) groups.pop(); break;
                    case "spPr": inSpPr = false; break;
                    case "xfrm": inXfrm = false; break;
                    case "ln": inLn = false; break;
                    case "bodyPr": inBodyPr = false; break;
                    case "rPr": inRPr = false; break;
                    case "t": inT = false; break;
                    case "r": case "fld": if (para != null && run != null) para.runs.add(run); run = null; break;
                    case "p": if (shape != null && para != null && !inTc) shape.paras.add(para); para = null; break;
                    case "tc": if (inTc) { tableRow.add(tcText.toString()); inTc = false; } break;
                    case "tr": if (inTable && shape != null && shape.table != null && tableRow != null) shape.table.add(tableRow); tableRow = null; break;
                    case "tbl": inTable = false; break;
                    case "sp": case "pic": case "cxnSp": case "graphicFrame":
                        if (shape != null) part.shapes.add(shape);
                        shape = null;
                        break;
                    default: break;
                }
            }
        });
        return part;
    }
}
