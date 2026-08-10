package cn.edu.suda.scholarsense.ingestionquality.application;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Canonical semantic JSON used by the V14 audit and PIC boundaries. */
final class DataBatchCanonicalJson {
    private static final BigInteger MAX_SAFE = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final Comparator<String> CODE_POINT_ORDER =
            DataBatchCanonicalJson::compareCodePoints;

    private DataBatchCanonicalJson() {}

    static byte[] bytes(Object value) {
        StringBuilder target = new StringBuilder();
        append(target, value);
        return target.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null");
        } else if (value instanceof String text) {
            string(target, text);
        } else if (value instanceof Boolean bool) {
            target.append(bool);
        } else if (value instanceof BigInteger integer) {
            integer(target, integer);
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            integer(target, BigInteger.valueOf(((Number) value).longValue()));
        } else if (value instanceof Map<?, ?> map) {
            object(target, map);
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

    private static void integer(StringBuilder target, BigInteger value) {
        if (value.abs().compareTo(MAX_SAFE) > 0) throw invalid();
        target.append(value);
    }

    private static void object(StringBuilder target, Map<?, ?> value) {
        List<String> names = new ArrayList<>(value.size());
        for (Object key : value.keySet()) {
            if (!(key instanceof String name)) throw invalid();
            scalars(name);
            names.add(name);
        }
        names.sort(CODE_POINT_ORDER);
        target.append('{');
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) target.append(',');
            String name = names.get(index);
            string(target, name);
            target.append(':');
            append(target, value.get(name));
        }
        target.append('}');
    }

    private static void string(StringBuilder target, String value) {
        scalars(value);
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

    private static void scalars(String value) {
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

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
    }
}
