package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DataBatchJdbcFailuresTest {
    @Test
    void finalOwnerClaimMismatchRetainsTheStableIdempotencyCode() {
        var jdbcFailure = failure(
                "ERROR: INGESTION_QUALITY_IDEMPOTENCY_MISMATCH\n  Where: owner claim",
                "23505");

        var translated = (IngestionQualityApplicationException)
                DataBatchJdbcFailures.translate(jdbcFailure);

        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", translated.code());
        assertSame(jdbcFailure, translated.getCause());
    }

    @Test
    void mapsOnlyWhitelistedOwnerMessagesToStableApplicationCodes() {
        assertEquals("INGESTION_QUALITY_VERSION_CONFLICT",
                translatedCode("INGESTION_QUALITY_VERSION_CONFLICT", "40001"));
        assertEquals("INGESTION_QUALITY_BATCH_NOT_FOUND",
                translatedCode("INGESTION_QUALITY_BATCH_NOT_FOUND", "P0002"));
        assertEquals("INGESTION_QUALITY_BATCH_IDENTITY_CONFLICT",
                translatedCode("INGESTION_QUALITY_BATCH_IDENTITY_CONFLICT", "23505"));
        assertEquals("INGESTION_QUALITY_CORRECTION_FORK",
                translatedCode(
                        "INGESTION_QUALITY_BATCH_LINEAGE_PREDECESSOR_NOT_HEAD", "23514"));
        assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_STATE_INVALID",
                translatedCode("INGESTION_QUALITY_NORMALIZED_FACT_STATE_INVALID", "23514"));
        assertEquals("INGESTION_QUALITY_MEASUREMENT_CONFLICT",
                translatedCode("INGESTION_QUALITY_MEASUREMENT_CONFLICT", "23505"));
        assertEquals("INGESTION_QUALITY_IMPACT_SCOPE_LIMIT_EXCEEDED",
                translatedCode("INGESTION_QUALITY_IMPACT_SCOPE_LIMIT_EXCEEDED", "23514"));
        assertEquals("INGESTION_QUALITY_IMPACT_SCOPE_INVALID",
                translatedCode("INGESTION_QUALITY_IMPACT_SCOPE_INVALID", "23514"));
        assertEquals("QUALITY_POLICY_ZERO_DENOMINATOR",
                translatedCode("QUALITY_POLICY_ZERO_DENOMINATOR", "23514"));
    }

    @Test
    void unknownMessagesAndWrongSqlStatesRemainOpaque() {
        var unknown = failure("ERROR: caller supplied message", "23505");
        assertSame(unknown, DataBatchJdbcFailures.translate(unknown));

        var wrongState = failure("ERROR: INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", "22000");
        assertSame(wrongState, DataBatchJdbcFailures.translate(wrongState));

        var prefixed = failure(
                "detail INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", "23505");
        assertSame(prefixed, DataBatchJdbcFailures.translate(prefixed));
    }

    private static String translatedCode(String message, String state) {
        return ((IngestionQualityApplicationException)
                DataBatchJdbcFailures.translate(failure(message, state))).code();
    }

    private static DataIntegrityViolationException failure(String message, String state) {
        return new DataIntegrityViolationException(
                "owner command failed", new SQLException(message, state));
    }
}
