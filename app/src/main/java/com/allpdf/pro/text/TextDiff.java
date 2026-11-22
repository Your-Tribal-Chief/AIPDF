package com.allpdf.pro.text;

import java.util.*;

/** Myers O(ND) line diff with modification pairing and word-level inline diff. */
public final class TextDiff {
    public enum Kind { SAME, ADDED, REMOVED, MODIFIED }

    public static final class Line {
        public final Kind kind; public final String left, right;
        public final List<Seg> leftSegs, rightSegs; // word-level for MODIFIED
        Line(Kind k, String l, String r, List<Seg> ls, List<Seg> rs) { kind = k; left = l; right = r; leftSegs = ls; rightSegs = rs; }
    }

    public static final class Seg {
        public final String text; public final boolean changed;
        Seg(String t, boolean c) { text = t; changed = c; }
    }

    public static final class Stats { public int same, added, removed, modified; }

    public static List<Line> diffLines(List<String> a, List<String> b) {
        List<int[]> ops = myers(a, b); // {type, ai, bi}  type 0 same, 1 del, 2 ins
        List<Line> out = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            int[] op = ops.get(i);
            if (op[0] == 0) { out.add(new Line(Kind.SAME, a.get(op[1]), b.get(op[2]), null, null)); i++; continue; }
            List<String> dels = new ArrayList<>(), ins = new ArrayList<>();
            while (i < ops.size() && ops.get(i)[0] != 0) {
                int[] o = ops.get(i++);
                if (o[0] == 1) dels.add(a.get(o[1])); else ins.add(b.get(o[2]));
            }
            int pairs = Math.min(dels.size(), ins.size());
            for (int k = 0; k < pairs; k++) {
                String l = dels.get(k), r = ins.get(k);
                if (similarity(l, r) >= 0.35) {
                    List<Seg>[] segs = wordDiff(l, r);
                    out.add(new Line(Kind.MODIFIED, l, r, segs[0], segs[1]));
                } else {
                    out.add(new Line(Kind.REMOVED, l, null, null, null));
                    out.add(new Line(Kind.ADDED, null, r, null, null));
                }
            }
            for (int k = pairs; k < dels.size(); k++) out.add(new Line(Kind.REMOVED, dels.get(k), null, null, null));
            for (int k = pairs; k < ins.size(); k++) out.add(new Line(Kind.ADDED, null, ins.get(k), null, null));
        }
        return out;
    }

    public static Stats stats(List<Line> lines) {
        Stats s = new Stats();
        for (Line l : lines) switch (l.kind) { case SAME: s.same++; break; case ADDED: s.added++; break; case REMOVED: s.removed++; break; default: s.modified++; }
        return s;
    }

    static double similarity(String a, String b) {
        Set<String> x = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.ROOT).split("\\s+")));
        Set<String> y = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.ROOT).split("\\s+")));
        if (x.isEmpty() && y.isEmpty()) return 1;
        Set<String> inter = new HashSet<>(x); inter.retainAll(y);
        Set<String> uni = new HashSet<>(x); uni.addAll(y);
        return (double) inter.size() / uni.size();
    }

    @SuppressWarnings("unchecked")
    static List<Seg>[] wordDiff(String l, String r) {
        List<String> a = Arrays.asList(l.split("(?<=\\s)|(?=\\s)"));
        List<String> b = Arrays.asList(r.split("(?<=\\s)|(?=\\s)"));
        List<int[]> ops = myers(a, b);
        List<Seg> ls = new ArrayList<>(), rs = new ArrayList<>();
        for (int[] o : ops) {
            if (o[0] == 0) { ls.add(new Seg(a.get(o[1]), false)); rs.add(new Seg(b.get(o[2]), false)); }
            else if (o[0] == 1) ls.add(new Seg(a.get(o[1]), true));
            else rs.add(new Seg(b.get(o[2]), true));
        }
        return new List[]{merge(ls), merge(rs)};
    }

    static List<Seg> merge(List<Seg> in) {
        List<Seg> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(); Boolean st = null;
        for (Seg s : in) {
            if (st != null && st != s.changed) { out.add(new Seg(cur.toString(), st)); cur.setLength(0); }
            st = s.changed; cur.append(s.text);
        }
        if (st != null) out.add(new Seg(cur.toString(), st));
        return out;
    }

    /** Returns edit script; falls back to a greedy diff when the edit distance is huge. */
    static List<int[]> myers(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        // trim common prefix/suffix
        int pre = 0;
        while (pre < n && pre < m && a.get(pre).equals(b.get(pre))) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre && a.get(n - 1 - suf).equals(b.get(m - 1 - suf))) suf++;
        List<int[]> ops = new ArrayList<>();
        for (int i = 0; i < pre; i++) ops.add(new int[]{0, i, i});
        List<String> A = a.subList(pre, n - suf), B = b.subList(pre, m - suf);
        List<int[]> mid = core(A, B);
        for (int[] o : mid) ops.add(new int[]{o[0], o[1] + pre, o[2] + pre});
        for (int i = 0; i < suf; i++) ops.add(new int[]{0, n - suf + i, m - suf + i});
        return ops;
    }

    private static List<int[]> core(List<String> a, List<String> b) {
        int n = a.size(), m = b.size(), max = n + m;
        List<int[]> ops = new ArrayList<>();
        if (n == 0) { for (int j = 0; j < m; j++) ops.add(new int[]{2, 0, j}); return ops; }
        if (m == 0) { for (int i = 0; i < n; i++) ops.add(new int[]{1, i, 0}); return ops; }
        int limit = Math.min(max, 4000);
        int off = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= limit; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) x = v[off + k + 1];
                else x = v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) { x++; y++; }
                v[off + k] = x;
                if (x >= n && y >= m) { found = d; break; }
            }
            if (found >= 0) break;
        }
        if (found < 0) {
            // Degenerate: everything removed then added
            for (int i = 0; i < n; i++) ops.add(new int[]{1, i, 0});
            for (int j = 0; j < m; j++) ops.add(new int[]{2, 0, j});
            return ops;
        }
        // backtrack
        int x = n, y = m;
        LinkedList<int[]> rev = new LinkedList<>();
        for (int d = found; d > 0; d--) {
            int[] pv = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && pv[off + k - 1] < pv[off + k + 1])) prevK = k + 1; else prevK = k - 1;
            int prevX = pv[off + prevK], prevY = prevX - prevK;
            while (x > prevX && y > prevY) { x--; y--; rev.addFirst(new int[]{0, x, y}); }
            if (x == prevX) { y--; rev.addFirst(new int[]{2, x, y}); }
            else { x--; rev.addFirst(new int[]{1, x, y}); }
        }
        while (x > 0 && y > 0) { x--; y--; rev.addFirst(new int[]{0, x, y}); }
        ops.addAll(rev);
        return ops;
    }
}
