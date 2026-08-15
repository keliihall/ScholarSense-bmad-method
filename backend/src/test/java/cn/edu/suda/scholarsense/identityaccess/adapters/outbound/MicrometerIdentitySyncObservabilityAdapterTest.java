package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MicrometerIdentitySyncObservabilityAdapterTest {

    @Test
    void exposesLowCardinalityCountersAndDatabaseBackedOperationalGauges() {
        var registry = new SimpleMeterRegistry();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("status in ('queued', 'running')"), eq(Long.class)))
                .thenReturn(3L);
        when(jdbc.queryForObject(contains("max(last_successful_at)"), eq(Double.class)))
                .thenReturn(1_700_000_000.0);
        when(jdbc.queryForObject(contains("max(source_watermark)"), eq(Long.class)))
                .thenReturn(42L);
        when(jdbc.queryForObject(contains("max(requested_to)"), eq(Long.class)))
                .thenReturn(2L);
        when(jdbc.queryForObject(contains("ia_identity_rejected_record"), eq(Long.class)))
                .thenReturn(4L);
        when(jdbc.queryForObject(
                contains("where authorization_effective_at"), eq(Long.class)))
                .thenReturn(100L);
        when(jdbc.queryForObject(
                contains("and within_fifteen_minutes"), eq(Long.class)))
                .thenReturn(99L);
        when(jdbc.queryForObject(
                contains("case when denominator.total=0"), eq(Double.class)))
                .thenReturn(0.99);
        when(jdbc.queryForObject(
                contains("fact.occurred_at"), eq(Double.class)))
                .thenReturn(901.0);
        when(jdbc.queryForObject(
                contains("job_kind in ('impact', 'expiry')"),
                eq(Long.class)))
                .thenReturn(5L);
        when(jdbc.queryForObject(
                contains("job_kind='impact'"), eq(Long.class)))
                .thenReturn(6L);
        when(jdbc.queryForObject(
                contains("job_kind='expiry'"), eq(Long.class)))
                .thenReturn(7L);
        var adapter = new MicrometerIdentitySyncObservabilityAdapter(registry, jdbc);

        adapter.record(new IdentitySyncObservation(
                "identity_sync_job_total",
                1,
                Map.of(
                        "feedId", "identity-authority",
                        "outcome", "succeeded",
                        "consumerProjection", "identity-org"),
                "0123456789abcdef0123456789abcdef",
                Instant.parse("2026-07-24T00:00:00Z")));

        assertEquals(1.0, registry.counter(
                "scholarsense.operation.total",
                "service", "scholarsense",
                "module", "identity-access",
                "operation", "identity-sync-job-total",
                "role", "identity-org",
                "dependency", "identity-authority",
                "outcome", "succeeded").count());
        assertEquals(3.0, gauge(registry, "identity_sync_job_backlog"));
        assertEquals(1_700_000_000.0,
                gauge(registry, "identity_sync_last_success_epoch_seconds"));
        assertEquals(42.0, gauge(registry, "identity_sync_checkpoint_watermark"));
        assertEquals(2.0, gauge(registry, "identity_sync_watermark_lag"));
        assertEquals(4.0, gauge(registry, "identity_sync_quarantine_count"));
        assertEquals(99.0, gauge(registry, "identity_sync_slo_30d_numerator"));
        assertEquals(100.0, gauge(registry, "identity_sync_slo_30d_denominator"));
        assertEquals(0.99, gauge(registry, "identity_sync_slo_30d_rate"));
        assertEquals(901.0, gauge(registry, "access_invalidation_lag_seconds"));
        assertEquals(5.0, gauge(registry, "access_invalidation_poison_count"));
        assertEquals(6.0, gauge(registry, "access_invalidation_fanout_backlog"));
        assertEquals(7.0, gauge(registry, "access_invalidation_expiry_backlog"));
        verify(jdbc, never()).queryForObject(
                contains("last_checked_at"), eq(Double.class));
    }

    @Test
    void feedDimensionKeepsApprovedFeedsInSeparateMetricSeries() {
        var registry = new SimpleMeterRegistry();
        var adapter = new MicrometerIdentitySyncObservabilityAdapter(registry);
        Instant observedAt = Instant.parse("2026-08-15T00:00:00Z");

        adapter.record(observation("identity-authority", observedAt));
        adapter.record(observation("responsibility-authority", observedAt));

        assertEquals(1.0, counter(registry, "identity-authority"));
        assertEquals(1.0, counter(registry, "responsibility-authority"));
    }

    private static IdentitySyncObservation observation(String feedId, Instant observedAt) {
        return new IdentitySyncObservation(
                "identity_sync_applied_total", 1,
                Map.of(
                        "sourceId", "SRC-P0-RESPONSIBILITY-001",
                        "feedId", feedId,
                        "consumerProjection", "identity-org",
                        "outcome", "applied"),
                "0123456789abcdef0123456789abcdef", observedAt);
    }

    private static double counter(SimpleMeterRegistry registry, String feedId) {
        return registry.get("scholarsense.operation.total")
                .tag("dependency", feedId).counter().count();
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
