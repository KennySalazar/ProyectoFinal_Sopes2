import java.util.*;

/** Serializador para snapshots compuestos por mapas, listas y valores simples. */
final class Json {
    private Json() { }
    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) {
            List<String> entries = new ArrayList<>();
            m.forEach((k, v) -> entries.add(encode(k.toString()) + ":" + encode(v)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> entries = new ArrayList<>();
            list.forEach(v -> entries.add(encode(v)));
            return "[" + String.join(",", entries) + "]";
        }
        StringBuilder s = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> { if (c < 32) s.append(String.format("\\u%04x", (int)c)); else s.append(c); }
            }
        }
        return s.append('"').toString();
    }
}
