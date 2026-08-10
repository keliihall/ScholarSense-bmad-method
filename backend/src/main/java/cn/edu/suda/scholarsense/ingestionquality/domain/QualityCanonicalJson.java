package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** QSHM canonical JSON: code-point key order, minimal escapes, UTF-8 and integer-only values. */
final class QualityCanonicalJson {
    private static final BigInteger MAX_SAFE_INTEGER =
            BigInteger.valueOf(IngestionQualityDomainRules.MAX_SAFE_VERSION);
    private static final Comparator<String> CODE_POINT_ORDER =
            QualityCanonicalJson::compareCodePoints;

    private QualityCanonicalJson() {}

    static byte[] canonicalUtf8(Object value) {
        StringBuilder target = new StringBuilder();
        append(target, value);
        return target.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null");
        } else if (value instanceof String text) {
            appendString(target, text);
        } else if (value instanceof Boolean bool) {
            target.append(bool);
        } else if (value instanceof BigInteger integer) {
            appendInteger(target, integer);
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            appendInteger(target, BigInteger.valueOf(((Number) value).longValue()));
        } else if (value instanceof Map<?, ?> map) {
            appendMap(target, map);
        } else if (value instanceof Iterable<?> values) {
            target.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) target.append(',');
                first = false;
                append(target, item);
            }
            target.append(']');
        } else {
            throw invalid();
        }
    }

    private static void appendInteger(StringBuilder target, BigInteger value) {
        if (value.signum() < 0 || value.compareTo(MAX_SAFE_INTEGER) > 0) throw invalid();
        target.append(value);
    }

    private static void appendMap(StringBuilder target, Map<?, ?> map) {
        List<String> names = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (!(key instanceof String name)) throw invalid();
            requireScalars(name);
            names.add(name);
        }
        names.sort(CODE_POINT_ORDER);
        target.append('{');
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) target.append(',');
            String name = names.get(index);
            appendString(target, name);
            target.append(':');
            append(target, map.get(name));
        }
        target.append('}');
    }

    private static void appendString(StringBuilder target, String value) {
        requireScalars(value);
        target.append('"');
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        target.append("\\u00")
                                .append(Character.forDigit((codePoint >>> 4) & 0xf, 16))
                                .append(Character.forDigit(codePoint & 0xf, 16));
                    } else {
                        target.appendCodePoint(codePoint);
                    }
                }
            }
        }
        target.append('"');
    }

    private static void requireScalars(String value) {
        if (value == null) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid();
                }
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                throw invalid();
            }
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftPoint = left.codePointAt(leftOffset);
            int rightPoint = right.codePointAt(rightOffset);
            if (leftPoint != rightPoint) return Integer.compare(leftPoint, rightPoint);
            leftOffset += Character.charCount(leftPoint);
            rightOffset += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    private static IngestionQualityException invalid() {
        return IngestionQualityDomainRules.invalid();
    }
}
