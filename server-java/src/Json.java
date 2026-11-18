
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.n) throw new IllegalArgumentException("JSON 끝에 남은 문자가 있습니다 (pos " + p.i + ")");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    public static double asDouble(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static final class P {
        final String s;
        final int n;
        int i;

        P(String s) { this.s = s; this.n = s.length(); }

        void ws() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') i++;
                else break;
            }
        }

        Object value() {
            if (i >= n) throw new IllegalArgumentException("JSON 이 갑자기 끝났습니다");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < n && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (i >= n || s.charAt(i) != ':') throw new IllegalArgumentException("':' 가 필요합니다 (pos " + i + ")");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= n) throw new IllegalArgumentException("객체가 닫히지 않았습니다");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("',' 또는 '}' 가 필요합니다 (pos " + (i - 1) + ")");
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < n && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (i >= n) throw new IllegalArgumentException("배열이 닫히지 않았습니다");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new IllegalArgumentException("',' 또는 ']' 가 필요합니다 (pos " + (i - 1) + ")");
            }
        }

        String string() {
            if (i >= n || s.charAt(i) != '"') throw new IllegalArgumentException("문자열이 필요합니다 (pos " + i + ")");
            i++;
            StringBuilder b = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("알 수 없는 이스케이프 \\" + e);
                }
            }
            throw new IllegalArgumentException("문자열이 닫히지 않았습니다");
        }

        Double number() {
            int st = i;
            if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < n) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') i++;
                else break;
            }
            if (st == i) throw new IllegalArgumentException("숫자가 필요합니다 (pos " + st + ")");
            return Double.valueOf(s.substring(st, i));
        }

        void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new IllegalArgumentException("'" + lit + "' 이 필요합니다 (pos " + i + ")");
            i += lit.length();
        }
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    public static String num(double v, int decimals) {
        String s = String.format("%." + decimals + "f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "0" : s;
    }
}
