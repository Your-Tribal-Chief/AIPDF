package com.allpdf.pro.office;

import java.util.ArrayList;
import java.util.List;

/** Format-neutral flow document used by Word/HTML/Text renderers and PDF->Word conversion. */
public final class DocModel {
    public abstract static class Block {}

    public static final class Run {
        public String text = "";
        public boolean bold, italic, underline;
        public float sizePt; // 0 = inherit
        public int color;    // 0 = default (black); otherwise 0xRRGGBB | 0xFF000000
        public Run() {}
        public Run(String t) { text = t; }
    }

    public static final class Para extends Block {
        public final List<Run> runs = new ArrayList<>();
        public int heading;      // 0 = body, 1..6 heading level, 9 = title
        public boolean bullet;
        public int numberedIndex; // >0 => "n." list item
        public int align;         // 0 left, 1 center, 2 right, 3 justify
        public int indentLevel;
        public String text() {
            StringBuilder b = new StringBuilder();
            for (Run r : runs) b.append(r.text);
            return b.toString();
        }
        public boolean isEmpty() { return text().trim().isEmpty(); }
    }

    public static final class Cell {
        public final List<Para> paras = new ArrayList<>();
        public int gridSpan = 1;
        public String text() {
            StringBuilder b = new StringBuilder();
            for (Para p : paras) { if (b.length() > 0) b.append('\n'); b.append(p.text()); }
            return b.toString();
        }
    }

    public static final class Table extends Block {
        public final List<List<Cell>> rows = new ArrayList<>();
        public int columns() {
            int m = 0;
            for (List<Cell> r : rows) { int c = 0; for (Cell x : r) c += Math.max(1, x.gridSpan); m = Math.max(m, c); }
            return m;
        }
    }

    public static final class Image extends Block {
        public byte[] data;
        public float widthPt, heightPt; // 0 => natural
        public Image(byte[] d, float w, float h) { data = d; widthPt = w; heightPt = h; }
    }

    public static final class PageBreak extends Block {}

    public final List<Block> blocks = new ArrayList<>();
    public String title = "";
}
