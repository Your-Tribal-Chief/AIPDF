package com.allpdf.pro.text;

import java.util.*;
import java.util.regex.*;

/**
 * Fully on-device extractive summarizer: TF-IDF sentence scoring (each sentence is a "document"),
 * blended with TextRank-style centrality, position and length priors, plus redundancy removal (MMR).
 */
public final class Summarizer {
    public static final class Result {
        public final List<String> keyPoints = new ArrayList<>();
        public final List<String> keywords = new ArrayList<>();
        public int sentenceCount, wordCount;
        public String toMarkdown(String title) {
            StringBuilder b = new StringBuilder();
            b.append("# Summary").append(title == null || title.isEmpty() ? "" : " of " + title).append("\n\n");
            b.append("_").append(wordCount).append(" words, ").append(sentenceCount).append(" sentences analysed on-device._\n\n");
            if (!keywords.isEmpty()) b.append("**Keywords:** ").append(String.join(", ", keywords)).append("\n\n");
            b.append("## Key points\n\n");
            for (String k : keyPoints) b.append("- ").append(k).append('\n');
            return b.toString();
        }
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList((
        "a an the and or but if then else when while of at by for with about against between into through during before after above below to from up down in out on off over under again further once here there all any both each few more most other some such no nor not only own same so than too very s t can will just don should now i me my myself we our ours ourselves you your yours yourself yourselves he him his himself she her hers herself it its itself they them their theirs themselves what which who whom this that these those am is are was were be been being have has had having do does did doing would could ought also may might must shall us via per however therefore thus hence within without upon whether either neither yet etc eg ie one two three new use used using based et al fig figure table page"
    ).split(" ")));

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'\\-]*");

    public static List<String> sentences(String text) {
        String t = text.replace('\r', '\n')
            .replaceAll("-\\n(\\p{Ll})", "$1")       // de-hyphenate line breaks
            .replaceAll("(?<![.!?:;])\\n(?!\\n)", " ") // join wrapped lines
            .replaceAll("[ \\t\\u00A0]+", " ");
        List<String> out = new ArrayList<>();
        // split on sentence terminators followed by space+capital/digit, or on blank lines
        Matcher m = Pattern.compile("(?<=[.!?\\u0964])[\"')\\]]*\\s+(?=[\\p{Lu}\\p{N}\"'(\\[\\u0980-\\u09FF])|\\n\\s*\\n").matcher(t);
        int last = 0;
        while (m.find()) { add(out, t.substring(last, m.start())); last = m.end(); }
        add(out, t.substring(last));
        return out;
    }

    private static void add(List<String> out, String s) {
        s = s.trim();
        if (s.isEmpty()) return;
        // very long chunks (lists without punctuation) are split on newlines/semicolons
        if (s.length() > 600) { for (String p : s.split("(?<=;)\\s+|\\n")) if (!p.trim().isEmpty()) out.add(p.trim()); }
        else out.add(s);
    }

    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 2 || STOP.contains(w) || w.chars().allMatch(Character::isDigit)) continue;
            out.add(stem(w));
        }
        return out;
    }

    /** Light suffix stripping so "models/modeling/modeled" collapse together. */
    static String stem(String w) {
        if (w.length() > 5) {
            for (String suf : new String[]{"ations", "ation", "ingly", "ings", "ing", "ness", "ments", "ment", "ities", "ity", "ies", "ed", "es", "s"}) {
                if (w.endsWith(suf) && w.length() - suf.length() >= 4) {
                    String r = w.substring(0, w.length() - suf.length());
                    return suf.equals("ies") || suf.equals("ities") ? r + "y" : r;
                }
            }
        } else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    public static Result summarize(String text, int maxPoints) {
        Result res = new Result();
        List<String> sents = sentences(text == null ? "" : text);
        List<String> keep = new ArrayList<>();
        for (String s : sents) {
            int words = s.split("\\s+").length;
            if (words >= 5 && words <= 80 && s.chars().filter(Character::isLetter).count() > s.length() * 0.5) keep.add(s);
        }
        res.sentenceCount = sents.size();
        res.wordCount = text == null ? 0 : text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        if (keep.isEmpty()) {
            for (String s : sents) if (res.keyPoints.size() < maxPoints) res.keyPoints.add(s);
            return res;
        }
        int n = keep.size();
        List<Map<String, Integer>> tf = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> total = new HashMap<>();
        for (String s : keep) {
            Map<String, Integer> m = new HashMap<>();
            for (String t : tokens(s)) { m.merge(t, 1, Integer::sum); total.merge(t, 1, Integer::sum); }
            for (String t : m.keySet()) df.merge(t, 1, Integer::sum);
            tf.add(m);
        }
        // TF-IDF vectors
        List<Map<String, Double>> vec = new ArrayList<>();
        double[] norm = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Double> v = new HashMap<>();
            double sq = 0;
            for (Map.Entry<String, Integer> e : tf.get(i).entrySet()) {
                double idf = Math.log((1.0 + n) / (1.0 + df.get(e.getKey()))) + 1.0;
                double w = (1 + Math.log(e.getValue())) * idf;
                v.put(e.getKey(), w); sq += w * w;
            }
            vec.add(v); norm[i] = Math.sqrt(sq);
        }
        // Document centroid weight per term (global importance)
        double[] tfidfScore = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (Map.Entry<String, Double> e : vec.get(i).entrySet()) s += e.getValue() * Math.log(1 + total.get(e.getKey()));
            int len = Math.max(1, tf.get(i).size());
            tfidfScore[i] = s / Math.sqrt(len);
        }
        // TextRank centrality (bounded for large docs)
        double[] rank = new double[n];
        Arrays.fill(rank, 1.0);
        if (n <= 1500) {
            double[][] sim = new double[n][];
            double[] rowSum = new double[n];
            for (int i = 0; i < n; i++) {
                sim[i] = new double[n];
                for (int j = 0; j < n; j++) if (i != j) { sim[i][j] = cosine(vec.get(i), norm[i], vec.get(j), norm[j]); rowSum[i] += sim[i][j]; }
            }
            for (int it = 0; it < 30; it++) {
                double[] nr = new double[n];
                for (int i = 0; i < n; i++) {
                    double acc = 0;
                    for (int j = 0; j < n; j++) if (sim[j][i] > 0 && rowSum[j] > 0) acc += sim[j][i] / rowSum[j] * rank[j];
                    nr[i] = 0.15 + 0.85 * acc;
                }
                rank = nr;
            }
        }
        double maxT = 1e-9, maxR = 1e-9;
        for (int i = 0; i < n; i++) { maxT = Math.max(maxT, tfidfScore[i]); maxR = Math.max(maxR, rank[i]); }
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            double pos = 1.0 - (double) i / n;
            double position = i < Math.max(2, n / 10) ? 0.15 : 0.05 * pos;
            score[i] = 0.55 * tfidfScore[i] / maxT + 0.35 * rank[i] / maxR + position;
        }
        // MMR selection
        int k = Math.min(maxPoints, Math.max(1, n));
        List<Integer> chosen = new ArrayList<>();
        boolean[] used = new boolean[n];
        while (chosen.size() < k) {
            int best = -1; double bestVal = -1e9;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                double red = 0;
                for (int c : chosen) red = Math.max(red, cosine(vec.get(i), norm[i], vec.get(c), norm[c]));
                double val = 0.75 * score[i] - 0.25 * red;
                if (val > bestVal) { bestVal = val; best = i; }
            }
            if (best < 0) break;
            used[best] = true;
            chosen.add(best);
        }
        Collections.sort(chosen);
        for (int i : chosen) res.keyPoints.add(keep.get(i));
        // keywords: highest global tf * idf, map stem back to most frequent surface form
        Map<String, Map<String, Integer>> surface = new HashMap<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 3 || STOP.contains(w)) continue;
            surface.computeIfAbsent(stem(w), x -> new HashMap<>()).merge(w, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> terms = new ArrayList<>(total.entrySet());
        terms.sort((a, b) -> Double.compare(
            b.getValue() * (Math.log((1.0 + n) / (1.0 + df.get(b.getKey()))) + 1.5),
            a.getValue() * (Math.log((1.0 + n) / (1.0 + df.get(a.getKey()))) + 1.5)));
        for (Map.Entry<String, Integer> e : terms) {
            if (res.keywords.size() >= 10) break;
            if (e.getValue() < 2 || e.getKey().length() < 3) continue;
            Map<String, Integer> forms = surface.get(e.getKey());
            String form = e.getKey();
            if (forms != null) form = Collections.max(forms.entrySet(), Map.Entry.comparingByValue()).getKey();
            res.keywords.add(form);
        }
        return res;
    }

    static double cosine(Map<String, Double> a, double na, Map<String, Double> b, double nb) {
        if (na == 0 || nb == 0) return 0;
        if (a.size() > b.size()) { Map<String, Double> t = a; a = b; b = t; }
        double dot = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) { Double o = b.get(e.getKey()); if (o != null) dot += e.getValue() * o; }
        return dot / (na * nb);
    }
}
