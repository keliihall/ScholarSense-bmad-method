package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DataBatchCanonicalOutboxFactoryTest {
    @Test
    void traceparentNeverDerivesAnAllZeroSpanIdFromAValidTraceId() {
        String traceId = "00000000000000001122334455667788";

        assertEquals("00-" + traceId + "-1122334455667788-01",
                DataBatchCanonicalOutboxFactory.traceparent(traceId));
        assertEquals(
                "00-00112233445566778899aabbccddeeff-0011223344556677-01",
                DataBatchCanonicalOutboxFactory.traceparent(
                        "00112233445566778899aabbccddeeff"));
    }
}
