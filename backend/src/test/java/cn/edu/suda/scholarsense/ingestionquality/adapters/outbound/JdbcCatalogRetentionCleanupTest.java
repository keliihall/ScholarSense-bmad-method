package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCatalogRetentionCleanupTest {
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final TimeSourceProfile PROFILE = new TimeSourceProfile(
            "campus-ntp-retention", "AUDIT-CLOCK-BINDING-1.0.0", 10,
            NOW.minusSeconds(5), NOW.plusSeconds(5),
            "evidence://signed/clock/retention.json");

    @Test
    void passesOnlyTheFreshTrustedCutoffToTheOwnerFunction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Timestamp cutoff = Timestamp.from(NOW);
        when(jdbc.queryForObject(
                anyString(), eq(Long.class), any(Timestamp.class), any(Timestamp.class),
                any(Timestamp.class), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(5L);

        long deleted = new JdbcCatalogRetentionCleanup(
                jdbc, () -> new TrustedTime(NOW, PROFILE)).cleanupExpired();

        assertEquals(5, deleted);
        verify(jdbc).queryForObject(
                contains("iq_cleanup_quality_finalization_expired"),
                eq(Long.class), eq(cutoff), eq(cutoff), eq(cutoff), eq(cutoff), eq(cutoff));
    }

    @Test
    void trustedTimeFailureStopsBeforeAnyDatabaseCall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcCatalogRetentionCleanup cleanup = new JdbcCatalogRetentionCleanup(jdbc, () -> {
            throw new IllegalStateException("trusted clock unavailable");
        });

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class, cleanup::cleanupExpired);

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", failure.code());
        verifyNoInteractions(jdbc);
    }
}
