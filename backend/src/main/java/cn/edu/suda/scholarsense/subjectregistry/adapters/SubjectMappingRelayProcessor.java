package cn.edu.suda.scholarsense.subjectregistry.adapters;

import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedConsumerPort;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingChangedEvent;
import cn.edu.suda.scholarsense.subjectregistry.api.SubjectMappingConsumptionOutcome;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingRelayClaim;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingRelayResult;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingRelayWorkPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Producer-owned relay: consumer commit succeeds before producer acknowledgement. */
public final class SubjectMappingRelayProcessor {
    private static final int BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private final SubjectMappingRelayWorkPort work;
    private final SubjectMappingChangedConsumerPort consumer;
    private final Clock clock;

    public SubjectMappingRelayProcessor(
            SubjectMappingRelayWorkPort work,
            SubjectMappingChangedConsumerPort consumer,
            Clock clock) {
        this.work = Objects.requireNonNull(work);
        this.consumer = Objects.requireNonNull(consumer);
        this.clock = Objects.requireNonNull(clock);
    }

    public SubjectMappingRelayResult runBatch() {
        List<SubjectMappingRelayClaim> claims = work.claimDue(BATCH_SIZE, clock.instant(), LEASE);
        int delivered = 0, retried = 0, failed = 0, fenced = 0;
        for (SubjectMappingRelayClaim claim : claims) {
            try {
                var source = claim.event();
                SubjectMappingConsumptionOutcome outcome = consumer.consume(
                        new SubjectMappingChangedEvent(
                                source.eventId(), source.aggregateId(), source.aggregateVersion(),
                                source.occurredAt(), source.correctionLineageId(), source.sourceId(),
                                source.affectedStudentRefs(), source.sourceWatermark(), source.traceId()));
                switch (outcome) {
                    case APPLIED, BACKFILL_APPLIED, DUPLICATE, OLD_VERSION -> {
                        if (work.confirm(source.eventId(), claim.attempts(), clock.instant())) delivered++;
                        else fenced++;
                    }
                    case GAP_PAUSED -> {
                        if (work.retry(source.eventId(), claim.attempts(), retryAt(claim),
                                "SUBJECT_MAPPING_EVENT_GAP")) retried++;
                        else fenced++;
                    }
                    case POISON_QUARANTINED -> {
                        if (work.fail(source.eventId(), claim.attempts(), clock.instant(),
                                "SUBJECT_MAPPING_EVENT_POISON")) failed++;
                        else fenced++;
                    }
                }
            } catch (RuntimeException unavailable) {
                if (work.retry(claim.event().eventId(), claim.attempts(), retryAt(claim),
                        "SUBJECT_MAPPING_CONSUMER_UNAVAILABLE")) retried++;
                else fenced++;
            }
        }
        return new SubjectMappingRelayResult(claims.size(), delivered, retried, failed, fenced);
    }

    private Instant retryAt(SubjectMappingRelayClaim claim) {
        long seconds = Math.min(3600, 1L << Math.min(12, claim.attempts()));
        return clock.instant().plusSeconds(seconds);
    }
}
