package cn.edu.suda.scholarsense.shared.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class W3cTraceIdTest {
    @Test
    void acceptsOnlyCanonicalLowercaseVersionZeroAndNeverPersistsTheRawHeader() {
        String raw = "00-0123456789ABCDEF0123456789ABCDEF-0123456789abcdef-01";
        String uppercaseFallback = W3cTraceId.from(raw, "fallback");
        assertTrue(uppercaseFallback.matches("[0-9a-f]{32}"));
        assertNotEquals("0123456789abcdef0123456789abcdef", uppercaseFallback);

        String canonical = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        assertEquals(
                "0123456789abcdef0123456789abcdef",
                W3cTraceId.from(canonical, "fallback"));

        String fallback = W3cTraceId.from("raw-user-controlled-header", "request:/current");
        String nextFallback = W3cTraceId.from("raw-user-controlled-header", "request:/current");
        assertTrue(fallback.matches("[0-9a-f]{32}"));
        assertNotEquals("raw-user-controlled-header", fallback);
        assertNotEquals(fallback, nextFallback);
    }
}
