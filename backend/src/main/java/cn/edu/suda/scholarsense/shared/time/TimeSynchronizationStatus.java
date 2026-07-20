package cn.edu.suda.scholarsense.shared.time;

import java.time.Instant;

/** Immutable observation collected by a deployment adapter outside the business transaction. */
public record TimeSynchronizationStatus(
        String sourceId,
        String profileVersion,
        int offsetMs,
        Instant observedAt,
        Instant freshUntil,
        String evidenceRef) {
    TimeSourceProfile profile() {
        return new TimeSourceProfile(
                sourceId, profileVersion, offsetMs, observedAt, freshUntil, evidenceRef);
    }
}
