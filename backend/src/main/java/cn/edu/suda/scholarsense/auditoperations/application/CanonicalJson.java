package cn.edu.suda.scholarsense.auditoperations.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Fixed canonical JSON encoder: UTF-8, sorted keys, no insignificant whitespace, no floating point. */
final class CanonicalJson {
    private CanonicalJson() {}

    static byte[] canonicalBytes(Object value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            appendString(text, output);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(map, output);
        } else if (value instanceof Iterable<?> items) {
            output.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                append(item, output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("AUDIT_CANONICAL_JSON_TYPE_FORBIDDEN");
        }
    }

    private static void appendMap(Map<?, ?> map, StringBuilder output) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("AUDIT_CANONICAL_JSON_KEY_INVALID");
            }
            entries.add(new AbstractMap.SimpleImmutableEntry<>(key, entry.getValue()));
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        output.append('{');
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            appendString(entries.get(index).getKey(), output);
            output.append(':');
            append(entries.get(index).getValue(), output);
        }
        output.append('}');
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append("\\u").append(String.format("%04x", codePoint));
                    } else if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
                        throw new IllegalArgumentException("AUDIT_CANONICAL_JSON_LONE_SURROGATE");
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
        }
        output.append('"');
    }
}
