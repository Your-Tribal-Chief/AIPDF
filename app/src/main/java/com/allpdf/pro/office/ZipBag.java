package com.allpdf.pro.office;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Reads an OOXML package (zip) into memory-friendly lookups. Entries over 64 MB are skipped. */
public final class ZipBag {
    private final Map<String, byte[]> entries = new HashMap<>();

    public ZipBag(File f) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n; long total = 0;
                boolean tooBig = false;
                while ((n = zis.read(buf)) > 0) {
                    total += n;
                    if (total > 64L * 1024 * 1024) { tooBig = true; break; }
                    bos.write(buf, 0, n);
                }
                if (!tooBig) entries.put(normalize(e.getName()), bos.toByteArray());
            }
        }
        if (entries.isEmpty()) throw new IOException("Not a valid Office Open XML (zip) file");
    }

    public static String normalize(String p) {
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        // resolve ".." segments
        Deque<String> parts = new ArrayDeque<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(s);
        }
        return String.join("/", parts);
    }

    public byte[] get(String path) { return entries.get(normalize(path)); }
    public boolean has(String path) { return entries.containsKey(normalize(path)); }

    public InputStream open(String path) {
        byte[] b = get(path);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    /** Directory part of a package path, e.g. "word/document.xml" -> "word/". */
    public static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    /** Relationship id -> absolute target path for a part. */
    public Map<String, String> rels(String partPath) {
        Map<String, String> out = new HashMap<>();
        String dir = dirOf(partPath);
        String name = partPath.substring(dir.length());
        byte[] data = get(dir + "_rels/" + name + ".rels");
        if (data == null) return out;
        try {
            Xml.parse(new ByteArrayInputStream(data), new Xml.Handler() {
                @Override public void start(String local, Xml.Attrs a) {
                    if (local.equals("Relationship")) {
                        String id = a.get("Id"), target = a.get("Target"), mode = a.get("TargetMode");
                        if (id != null && target != null && !"External".equals(mode)) {
                            out.put(id, target.startsWith("/") ? normalize(target) : normalize(dir + target));
                        }
                    }
                }
            });
        } catch (Exception ignored) { }
        return out;
    }

    public Map<String, String> relTypes(String partPath) {
        Map<String, String> out = new HashMap<>();
        String dir = dirOf(partPath);
        String name = partPath.substring(dir.length());
        byte[] data = get(dir + "_rels/" + name + ".rels");
        if (data == null) return out;
        try {
            Xml.parse(new ByteArrayInputStream(data), new Xml.Handler() {
                @Override public void start(String local, Xml.Attrs a) {
                    if (local.equals("Relationship")) {
                        String id = a.get("Id"), type = a.get("Type");
                        if (id != null && type != null) out.put(id, type);
                    }
                }
            });
        } catch (Exception ignored) { }
        return out;
    }
}
