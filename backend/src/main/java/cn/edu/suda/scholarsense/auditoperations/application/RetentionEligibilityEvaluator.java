package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Evaluates the exact UTC calendar-year boundary and half-open legal-hold window. */
public final class RetentionEligibilityEvaluator {
    public RetentionEvaluation evaluate(
            RetentionSchedule schedule,
            List<RetentionCandidate> candidates,
            List<AuditLegalHold> holds,
            Instant now) {
        List<String> eligible = new ArrayList<>();
        List<String> held = new ArrayList<>();
        List<String> notDue = new ArrayList<>();
        for (RetentionCandidate candidate : candidates) {
            Instant retentionEnd = candidate.occurredAt().atZone(ZoneOffset.UTC)
                    .plusYears(schedule.retentionYears()).toInstant();
            if (now.isBefore(retentionEnd)) {
                notDue.add(candidate.fixtureRecordId());
            } else if (holds.stream().anyMatch(hold -> hold.appliesTo(candidate.scope(), now))) {
                held.add(candidate.fixtureRecordId());
            } else {
                eligible.add(candidate.fixtureRecordId());
            }
        }
        return new RetentionEvaluation(eligible, held, notDue);
    }
}
