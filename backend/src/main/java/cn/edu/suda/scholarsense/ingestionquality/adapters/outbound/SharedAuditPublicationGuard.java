package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogPublicationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogSnapshot;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** High-risk publication gate. Missing, stale, unhealthy or permanently failed audit state fails closed. */
public final class SharedAuditPublicationGuard implements CatalogPublicationGuard {
    private static final Duration MAX_BACKLOG_AGE = Duration.ofMinutes(15);
    private static final Duration MAX_OBSERVATION_AGE = Duration.ofMinutes(5);
    private final AuditAvailabilityPort availability;
    private final AuditProducerBacklogPort backlog;
    private final Clock clock;

    public SharedAuditPublicationGuard(
            AuditAvailabilityPort availability, AuditProducerBacklogPort backlog, Clock clock) {
        this.availability = Objects.requireNonNull(availability);
        this.backlog = Objects.requireNonNull(backlog);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void requireAvailable(String traceId) {
        try {
            Instant now = clock.instant();
            if (!availability.current(traceId).allowsHighRiskAt(now)) unavailable();
            AuditProducerBacklogSnapshot snapshot = backlog.current();
            if (!snapshot.available() || snapshot.permanentFailureActive()
                    || snapshot.measuredAt().isBefore(now.minus(MAX_OBSERVATION_AGE))
                    || snapshot.oldestUnconfirmedAgeSeconds() > MAX_BACKLOG_AGE.toSeconds()) {
                unavailable();
            }
        } catch (IngestionQualityApplicationException blocked) {
            throw blocked;
        } catch (RuntimeException unavailable) {
            unavailable();
        }
    }

    private static void unavailable() {
        throw new IngestionQualityApplicationException("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }
}
