package com.allpdf.pro.office;

import java.util.*;

public final class SheetModel {
    public static final class Sheet {
        public String name = "Sheet";
        /** row index -> (col index -> display text). 0-based. */
        public final TreeMap<Integer, TreeMap<Integer, String>> cells = new TreeMap<>();
        public final Set<String> numeric = new HashSet<>(); // "r:c" of numeric cells (right-aligned)
        public final Set<String> bold = new HashSet<>();
        public final Map<Integer, Float> colWidthChars = new HashMap<>();
        public void put(int r, int c, String v) {
            if (v == null || v.isEmpty()) return;
            cells.computeIfAbsent(r, k -> new TreeMap<>()).put(c, v);
        }
        public int maxCol() {
            int m = -1;
            for (TreeMap<Integer, String> row : cells.values()) if (!row.isEmpty()) m = Math.max(m, row.lastKey());
            return m;
        }
        public int maxRow() { return cells.isEmpty() ? -1 : cells.lastKey(); }
    }
    public final List<Sheet> sheets = new ArrayList<>();

    public static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(Locale.US, "%.10g", d).trim();
        if (s.contains("e") || s.contains("E")) return s;
        if (s.contains(".")) { s = s.replaceAll("0+$", ""); if (s.endsWith(".")) s = s.substring(0, s.length() - 1); }
        return s;
    }

    /** Excel serial date (1900 system) -> yyyy-MM-dd [HH:mm]. */
    public static String excelDate(double serial) {
        long days = (long) Math.floor(serial);
        double frac = serial - days;
        String date;
        if (days == 60) date = "1900-02-29"; // Excel's fictitious leap day
        else {
            java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            if (days < 60) cal.set(1899, java.util.Calendar.DECEMBER, 31); else cal.set(1899, java.util.Calendar.DECEMBER, 30);
            cal.add(java.util.Calendar.DATE, (int) days);
            date = String.format(Locale.US, "%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH));
        }
        if (frac > 1e-6) {
            int secs = (int) Math.round(frac * 86400);
            date += String.format(Locale.US, " %02d:%02d", secs / 3600, (secs / 60) % 60);
        }
        return date;
    }

    /** "BC12" -> {row 11, col 54}. */
    public static int[] ref(String r) {
        int col = 0, i = 0;
        while (i < r.length() && Character.isLetter(r.charAt(i))) { col = col * 26 + (Character.toUpperCase(r.charAt(i)) - 'A' + 1); i++; }
        int row = 0;
        try { row = Integer.parseInt(r.substring(i)); } catch (Exception ignored) {}
        return new int[]{row - 1, col - 1};
    }

    public static String colName(int c) {
        StringBuilder b = new StringBuilder();
        c++;
        while (c > 0) { int m = (c - 1) % 26; b.insert(0, (char) ('A' + m)); c = (c - 1) / 26; }
        return b.toString();
    }
}
