package com.allpdf.pro.office;

import java.util.ArrayList;
import java.util.List;

public final class SlideModel {
    public long widthEmu = 12192000L, heightEmu = 6858000L; // 16:9 default

    public static final class Shape {
        public long x, y, w, h;
        public boolean hasXfrm;
        public final List<DocModel.Para> paras = new ArrayList<>();
        public List<List<String>> table;  // graphicFrame tables
        public byte[] image;
        public int fill;       // 0 = none, else ARGB
        public int line;       // 0 = none, else ARGB
        public String geom = "rect";
        public String phType, phIdx;
        public int rotation;   // degrees
        public int anchor;     // 0 top, 1 middle, 2 bottom
        public float defaultSize; // pt; 0 = renderer decides
    }

    public static final class Slide {
        public final List<Shape> shapes = new ArrayList<>();
        public int background;     // 0 = white
        public byte[] backgroundImage;
        public String notes = "";
    }

    public final List<Slide> slides = new ArrayList<>();
}
