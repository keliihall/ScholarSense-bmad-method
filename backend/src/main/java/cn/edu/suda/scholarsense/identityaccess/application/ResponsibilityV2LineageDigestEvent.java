package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/** One event in the source/consumer interoperable lineage digest profile. */
public record ResponsibilityV2LineageDigestEvent(
        long lineageVersion,
        UUID eventId,
        UUID supersedesId,
        long sourceVersion,
        long sourceWatermark,
        long recordVersion,
        String payloadDigest,
        String changeKind,
        String reasonCode,
        Instant effectiveAt) {
    private static final Set<String> CHANGE_KINDS = Set.of(
            "corrected", "revoked", "expired", "invalidated",
            "revalidated");
    private static final Set<String> REASON_CODES = Set.of(
            "DIRECT_RESPONSIBILITY_CHANGE",
            "RELATION_EXPIRED",
            "COMPLETE_SNAPSHOT_MISSING",
            "QUALITY_GATE_INVALID",
            "RECONCILIATION_RECOVERED",
            "SOURCE_CORRECTION");

    public ResponsibilityV2LineageDigestEvent {
        if (lineageVersion < 1
                || eventId == null
                || eventId.version() != 7
                || eventId.variant() != 2
                || supersedesId != null
                        && (supersedesId.version() != 7
                                || supersedesId.variant() != 2)
                || sourceVersion < 1
                || sourceWatermark < 1
                || recordVersion < 1
                || payloadDigest == null
                || !payloadDigest.matches("[0-9a-f]{64}")
                || !CHANGE_KINDS.contains(changeKind)
                || !REASON_CODES.contains(reasonCode)
                || effectiveAt == null
                || !effectiveAt.equals(
                        effectiveAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_LINEAGE_DIGEST_EVENT_INVALID");
        }
    }
}
