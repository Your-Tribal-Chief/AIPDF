package com.allpdf.pro.office;

import java.io.InputStream;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Tiny namespace-agnostic SAX wrapper (works identically on Android and the JDK). */
public final class Xml {
    private Xml() {}

    public static final class Attrs {
        private Attributes a;
        void set(Attributes a) { this.a = a; }
        /** Lookup by local name, ignoring prefix. */
        public String get(String local) {
            if (a == null) return null;
            for (int i = 0; i < a.getLength(); i++) {
                String ln = a.getLocalName(i);
                if (ln == null || ln.isEmpty()) ln = a.getQName(i);
                int c = ln.indexOf(':');
                if (c >= 0) ln = ln.substring(c + 1);
                if (ln.equals(local)) return a.getValue(i);
            }
            return null;
        }
        /** Lookup by exact qualified name, e.g. "r:embed". */
        public String q(String qname) { return a == null ? null : a.getValue(qname); }
        public int getInt(String local, int def) {
            String v = get(local);
            if (v == null) return def;
            try { return Integer.parseInt(v.trim()); } catch (Exception e) {
                try { return (int) Double.parseDouble(v.trim()); } catch (Exception e2) { return def; }
            }
        }
        public long getLong(String local, long def) {
            String v = get(local);
            if (v == null) return def;
            try { return Long.parseLong(v.trim()); } catch (Exception e) { return def; }
        }
    }

    public abstract static class Handler {
        public void start(String local, Attrs a) {}
        public void end(String local) {}
        public void text(char[] ch, int start, int len) {}
    }

    private static String localOf(String localName, String qName) {
        String n = localName != null && !localName.isEmpty() ? localName : qName;
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    public static void parse(InputStream in, Handler h) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        SAXParser p = f.newSAXParser();
        final Attrs attrs = new Attrs();
        p.parse(in, new DefaultHandler() {
            @Override public void startElement(String uri, String localName, String qName, Attributes atts) {
                attrs.set(atts);
                h.start(localOf(localName, qName), attrs);
            }
            @Override public void endElement(String uri, String localName, String qName) {
                h.end(localOf(localName, qName));
            }
            @Override public void characters(char[] ch, int start, int length) {
                h.text(ch, start, length);
            }
        });
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    // strip characters illegal in XML 1.0
                    if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                            || Character.isSurrogate(c)) b.append(c);
            }
        }
        return b.toString();
    }
}
