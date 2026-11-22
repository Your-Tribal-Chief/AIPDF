package com.allpdf.pro.text;

import java.util.*;
import java.util.regex.Pattern;

/** Reconstructs document structure (headings, paragraphs, bullets, tables) from positioned words. */
public final class Layout {

    public static final class Word {
        public String text; public float x0, x1, top, bottom, size; public boolean bold, italic;
        public Word(String t, float x0, float x1, float top, float bottom, float size, boolean bold) {
            text = t; this.x0 = x0; this.x1 = x1; this.top = top; this.bottom = bottom; this.size = size; this.bold = bold;
        }
    }

    public static final class Line {
        public final List<Word> words = new ArrayList<>();
        public float top, bottom, x0, x1, size; public boolean bold;
        public String text() {
            StringBuilder b = new StringBuilder();
            Word prev = null;
            for (Word w : words) {
                if (prev != null) b.append(w.x0 - prev.x1 > Math.max(0.15f * w.size, 0.5f) ? " " : "");
                b.append(w.text); prev = w;
            }
            return b.toString().trim();
        }
        void finish() {
            words.sort(Comparator.comparingDouble(w -> w.x0));
            top = Float.MAX_VALUE; bottom = -Float.MAX_VALUE; x0 = Float.MAX_VALUE; x1 = -Float.MAX_VALUE;
            float sizeAcc = 0; int chars = 0, boldChars = 0;
            for (Word w : words) {
                top = Math.min(top, w.top); bottom = Math.max(bottom, w.bottom);
                x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1);
                int c = Math.max(1, w.text.length()); chars += c; sizeAcc += w.size * c; if (w.bold) boldChars += c;
            }
            size = chars == 0 ? 10 : sizeAcc / chars;
            bold = boldChars > chars * 0.6;
        }
    }

    public static final class Page {
        public float width, height;
        public final List<Line> lines = new ArrayList<>();
    }

    public enum Kind { HEADING, PARAGRAPH, BULLET, TABLE, PAGE_BREAK }

    public static final class Item {
        public Kind kind; public int level; public String text; public List<List<String>> rows; public boolean bold;
        public int page;
        Item(Kind k) { kind = k; }
    }

    /** Groups words into lines by vertical overlap. Words must use top-down coordinates. */
    public static List<Line> toLines(List<Word> words) {
        List<Word> ws = new ArrayList<>(words);
        ws.sort((a, b) -> {
            float ca = (a.top + a.bottom) / 2, cb = (b.top + b.bottom) / 2;
            return Float.compare(ca, cb) != 0 ? Float.compare(ca, cb) : Float.compare(a.x0, b.x0);
        });
        List<Line> lines = new ArrayList<>();
        Line cur = null; float curMid = 0, curH = 0;
        for (Word w : ws) {
            float mid = (w.top + w.bottom) / 2, h = Math.max(1, w.bottom - w.top);
            if (cur != null && Math.abs(mid - curMid) < Math.max(curH, h) * 0.5f) {
                cur.words.add(w);
            } else {
                if (cur != null) { cur.finish(); lines.add(cur); }
                cur = new Line(); cur.words.add(w); curMid = mid; curH = h;
            }
        }
        if (cur != null) { cur.finish(); lines.add(cur); }
        // Split lines that contain columns of a 2-column layout? Kept as-is: tables handle wide gaps.
        return lines;
    }

    static final Pattern BULLET = Pattern.compile("^\\s*([\\u2022\\u25CF\\u25CB\\u25E6\\u25AA\\u25A0\\u25A1\\u25BA\\u25B8\\u2023\\u2043\\u2219\\u00B7\\-\\u2013\\u2014*\\uF0B7\\uF0A7]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)])\\s+.*");
    static final Pattern SYMBOL_BULLET = Pattern.compile("^\\s*[\\u2022\\u25CF\\u25CB\\u25E6\\u25AA\\u25A0\\u25A1\\u25BA\\u25B8\\u2023\\u2043\\u2219\\u00B7\\-\\u2013\\u2014*\\uF0B7\\uF0A7]\\s+");

    public static String stripSymbolBullet(String s) { return SYMBOL_BULLET.matcher(s).replaceFirst(""); }
    public static boolean isSymbolBullet(String s) { return SYMBOL_BULLET.matcher(s).find(); }

    /** Splits a line into cells separated by wide horizontal gaps. */
    public static List<List<Word>> cells(Line l) {
        List<List<Word>> out = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        Word prev = null;
        for (Word w : l.words) {
            float gapLimit = Math.max(w.size * 1.6f, 9f);
            if (prev != null && w.x0 - prev.x1 > gapLimit) { out.add(cur); cur = new ArrayList<>(); }
            cur.add(w); prev = w;
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    static String join(List<Word> ws) {
        StringBuilder b = new StringBuilder(); Word p = null;
        for (Word w : ws) { if (p != null && w.x0 - p.x1 > 0.15f * w.size) b.append(' '); b.append(w.text); p = w; }
        return b.toString().trim();
    }

    /** Column anchors from cell left edges (clustered), sorted. */
    static float[] anchors(List<Line> lines, float tol) {
        List<Float> xs = new ArrayList<>();
        for (Line l : lines) for (List<Word> c : cells(l)) xs.add(c.get(0).x0);
        Collections.sort(xs);
        List<float[]> clusters = new ArrayList<>(); // {sum, count, last}
        for (float x : xs) {
            if (!clusters.isEmpty() && x - clusters.get(clusters.size() - 1)[2] <= tol) {
                float[] c = clusters.get(clusters.size() - 1); c[0] += x; c[1]++; c[2] = x;
            } else clusters.add(new float[]{x, 1, x});
        }
        int minSupport = Math.max(2, lines.size() / 3);
        List<Float> out = new ArrayList<>();
        for (float[] c : clusters) if (c[1] >= minSupport) out.add(c[0] / c[1]);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Converts lines into a grid using column anchors (used for tables and PDF->Excel). */
    public static List<List<String>> grid(List<Line> lines) {
        List<List<String>> rows = new ArrayList<>();
        if (lines.isEmpty()) return rows;
        float tol = 0;
        for (Line l : lines) tol += l.size;
        tol = Math.max(4f, tol / lines.size() * 0.9f);
        float[] anc = anchors(lines, tol);
        if (anc.length < 2) {
            for (Line l : lines) { List<String> r = new ArrayList<>(); for (List<Word> c : cells(l)) r.add(join(c)); rows.add(r); }
            return rows;
        }
        for (Line l : lines) {
            String[] row = new String[anc.length];
            for (List<Word> c : cells(l)) {
                float x = c.get(0).x0;
                int best = 0;
                for (int i = 0; i < anc.length; i++) if (anc[i] <= x + tol) best = i;
                String v = join(c);
                row[best] = row[best] == null ? v : row[best] + " " + v;
            }
            List<String> r = new ArrayList<>();
            for (String s : row) r.add(s == null ? "" : s);
            rows.add(r);
        }
        return rows;
    }

    /** Body font size: char-weighted mode of rounded sizes. */
    public static float bodySize(List<Page> pages) {
        Map<Integer, Integer> hist = new HashMap<>();
        for (Page p : pages) for (Line l : p.lines) hist.merge(Math.round(l.size * 2), l.text().length(), Integer::sum);
        int best = 20, bestN = -1;
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        return best / 2f;
    }

    public static List<Item> analyze(List<Page> pages) {
        float body = bodySize(pages);
        // heading levels by distinct larger sizes
        TreeSet<Integer> big = new TreeSet<>(Collections.reverseOrder());
        for (Page p : pages) for (Line l : p.lines) if (l.size >= body * 1.18f && l.text().length() < 160) big.add(Math.round(l.size));
        List<Integer> levels = new ArrayList<>(big);

        List<Item> items = new ArrayList<>();
        for (int pi = 0; pi < pages.size(); pi++) {
            Page page = pages.get(pi);
            if (pi > 0) { Item br = new Item(Kind.PAGE_BREAK); br.page = pi; items.add(br); }
            List<Line> lines = new ArrayList<>(page.lines);
            lines.removeIf(l -> l.text().isEmpty());
            // drop likely page numbers / running headers (tiny lone numeric lines near edges)
            lines.removeIf(l -> l.text().matches("^(page\\s*)?\\d{1,4}(\\s*(/|of)\\s*\\d{1,4})?$") && (l.top < page.height * 0.08 || l.bottom > page.height * 0.92));
            int i = 0;
            Item para = null; Line prevLine = null;
            while (i < lines.size()) {
                // table detection: >=3 consecutive multi-cell lines with shared anchors
                int j = i;
                while (j < lines.size() && cells(lines.get(j)).size() >= 2) j++;
                if (j - i >= 3) {
                    List<Line> block = lines.subList(i, j);
                    float tol = Math.max(4f, body * 0.9f);
                    if (anchors(block, tol).length >= 2) {
                        para = null;
                        Item t = new Item(Kind.TABLE); t.rows = grid(block); t.page = pi; items.add(t);
                        prevLine = lines.get(j - 1); i = j; continue;
                    }
                }
                Line l = lines.get(i);
                String text = l.text();
                int lvl = 0;
                int idx = levels.indexOf(Math.round(l.size));
                if (idx >= 0 && l.size >= body * 1.18f) lvl = Math.min(3, idx + 1);
                else if (l.bold && text.length() < 90 && !text.endsWith(".") && !text.endsWith(",") && l.size >= body * 0.95f
                        && (prevLine == null || l.top - prevLine.bottom > l.size * 0.6f)) lvl = 3;
                if (lvl > 0 && text.length() < 200) {
                    // merge multi-line headings of same size
                    Item last = items.isEmpty() ? null : items.get(items.size() - 1);
                    if (last != null && last.kind == Kind.HEADING && last.level == lvl && prevLine != null && l.top - prevLine.bottom < l.size * 0.8f && para == null)
                        last.text += " " + text;
                    else { Item h = new Item(Kind.HEADING); h.level = lvl; h.text = text; h.page = pi; items.add(h); }
                    para = null; prevLine = l; i++; continue;
                }
                boolean bullet = BULLET.matcher(text).matches();
                boolean newPara = para == null || bullet || prevLine == null
                        || l.top - prevLine.bottom > Math.max(l.size, prevLine.size) * 0.9f
                        || Math.abs(l.size - prevLine.size) > 1.5f
                        || (prevLine.text().matches(".*[.!?:]$") && prevLine.x1 < page.width * 0.6f);
                if (newPara) {
                    para = new Item(bullet ? Kind.BULLET : Kind.PARAGRAPH); para.text = text; para.page = pi; para.bold = l.bold;
                    items.add(para);
                } else {
                    if (para.text.endsWith("-") && !para.text.endsWith(" -")) para.text = para.text.substring(0, para.text.length() - 1) + text;
                    else para.text += " " + text;
                }
                prevLine = l; i++;
            }
        }
        return items;
    }

    public static String toMarkdown(List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item it : items) {
            switch (it.kind) {
                case HEADING: b.append("\n"); for (int h = 0; h < Math.max(1, it.level); h++) b.append("#"); b.append(' ').append(it.text.trim()).append("\n\n"); break;
                case BULLET:
                    if (isSymbolBullet(it.text)) b.append("- ").append(mdEsc(stripSymbolBullet(it.text))).append('\n');
                    else b.append(mdEsc(it.text.trim())).append('\n');
                    break;
                case PARAGRAPH: b.append(it.bold ? "**" + mdEsc(it.text.trim()) + "**" : mdEsc(it.text.trim())).append("\n\n"); break;
                case TABLE: {
                    if (it.rows.isEmpty()) break;
                    int cols = 0; for (List<String> r : it.rows) cols = Math.max(cols, r.size());
                    b.append('\n');
                    for (int r = 0; r < it.rows.size(); r++) {
                        b.append('|');
                        for (int c = 0; c < cols; c++) {
                            String v = c < it.rows.get(r).size() ? it.rows.get(r).get(c) : "";
                            b.append(' ').append(v.replace("|", "\\|").trim()).append(" |");
                        }
                        b.append('\n');
                        if (r == 0) { b.append('|'); for (int c = 0; c < cols; c++) b.append(" --- |"); b.append('\n'); }
                    }
                    b.append('\n');
                    break;
                }
                case PAGE_BREAK: b.append("\n---\n\n"); break;
            }
        }
        return b.toString().replaceAll("\n{3,}", "\n\n").trim() + "\n";
    }

    static String mdEsc(String s) { return s.replaceAll("^([#>])", "\\\\$1"); }
}
