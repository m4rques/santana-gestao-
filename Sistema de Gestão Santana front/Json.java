import java.math.BigDecimal;
import java.util.*;

// JSON mínimo (sem bibliotecas): lê o corpo das requisições e escreve as respostas.
public class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    // ---------- leitura ----------
    @SuppressWarnings("unchecked")
    static Map<String, Object> objeto(String texto) {
        if (texto == null || texto.isBlank()) return new HashMap<>();
        try {
            Json j = new Json(texto);
            j.ws();
            Object v = j.valor();
            j.ws();
            if (v instanceof Map && j.i == texto.length()) return (Map<String, Object>) v;
        } catch (RuntimeException e) { /* cai no erro abaixo */ }
        throw ErroNegocio.invalido("JSON inválido no corpo da requisição.");
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private void esperar(char c) {
        if (s.charAt(i) != c) throw new IllegalStateException();
        i++;
    }

    private Object valor() {
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = texto(); ws(); esperar(':'); ws();
                m.put(k, valor()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                esperar('}');
                return m;
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(valor()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                esperar(']');
                return l;
            }
        }
        if (c == '"') return texto();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (ini == i) throw new IllegalStateException();
        return new BigDecimal(s.substring(ini, i));
    }

    private String texto() {
        esperar('"');
        StringBuilder b = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
        i++;
        return b.toString();
    }

    // ---------- acesso aos campos ----------
    static String txt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        String t = v.toString().trim();
        return t.isEmpty() ? null : t;
    }

    static BigDecimal num(Map<String, Object> m, String k) {
        String t = txt(m, k);
        if (t == null) return null;
        try { return new BigDecimal(t); }
        catch (NumberFormatException e) { throw ErroNegocio.invalido("Valor numérico inválido em '" + k + "'."); }
    }

    static Long longo(Map<String, Object> m, String k) {
        BigDecimal n = num(m, k);
        return n == null ? null : n.longValue();
    }

    // ---------- escrita ----------
    static String escrever(Object o) {
        StringBuilder b = new StringBuilder();
        w(b, o);
        return b.toString();
    }

    private static void w(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof Map<?, ?> m) {
            b.append('{');
            boolean primeiro = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primeiro) b.append(',');
                primeiro = false;
                str(b, String.valueOf(e.getKey()));
                b.append(':');
                w(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable<?> it) {
            b.append('[');
            boolean primeiro = true;
            for (Object x : it) {
                if (!primeiro) b.append(',');
                primeiro = false;
                w(b, x);
            }
            b.append(']');
        } else if (o instanceof BigDecimal d) b.append(d.toPlainString());
        else if (o instanceof Number || o instanceof Boolean) b.append(o);
        else str(b, o.toString());
    }

    private static void str(StringBuilder b, String t) {
        b.append('"');
        for (char c : t.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
}
