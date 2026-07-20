package cn.edu.suda.scholarsense.shared.trace;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses W3C traceparent and exposes only the normalized 16-byte trace-id. */
public final class W3cTraceId {
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^(?!ff)([0-9A-Fa-f]{2})-([0-9A-Fa-f]{32})-([0-9A-Fa-f]{16})-([0-9A-Fa-f]{2})$");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_PARENT = "0".repeat(16);
    private static final SecureRandom RANDOM = new SecureRandom();

    private W3cTraceId() {}

    public static String from(String traceparent, String fallbackSeed) {
        if (traceparent != null) {
            Matcher match = TRACEPARENT.matcher(traceparent.strip());
            if (match.matches()) {
                String traceId = match.group(2).toLowerCase(Locale.ROOT);
                String parentId = match.group(3).toLowerCase(Locale.ROOT);
                if (!ZERO_TRACE.equals(traceId) && !ZERO_PARENT.equals(parentId)) {
                    return traceId;
                }
            }
        }
        byte[] traceId = new byte[16];
        do {
            RANDOM.nextBytes(traceId);
        } while (allZero(traceId));
        return HexFormat.of().formatHex(traceId);
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }
}
