package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.List;

public record RetentionGuardSnapshot(
        RetentionSchedule schedule,
        TrustedAuditTime trustedTime,
        boolean ledgerHealthy,
        boolean rangeVerified,
        boolean archiveReadBackVerified,
        long sourceLedgerHead,
        long projectionWatermark,
        String archiveDigest,
        long guardVersion,
        List<AuditLegalHold> holds,
        List<RetentionCandidate> candidates) {
    public RetentionGuardSnapshot {
        holds = List.copyOf(holds);
        candidates = List.copyOf(candidates);
    }

    public RetentionGuardSnapshot withTrustedTime(TrustedAuditTime replacement) {
        return new RetentionGuardSnapshot(schedule, replacement, ledgerHealthy, rangeVerified,
                archiveReadBackVerified, sourceLedgerHead, projectionWatermark, archiveDigest,
                guardVersion, holds, candidates);
    }

    public RetentionGuardSnapshot withSchedule(RetentionSchedule replacement) {
        return new RetentionGuardSnapshot(replacement, trustedTime, ledgerHealthy, rangeVerified,
                archiveReadBackVerified, sourceLedgerHead, projectionWatermark, archiveDigest,
                guardVersion, holds, candidates);
    }

    public RetentionGuardSnapshot withArchiveVerified(boolean replacement) {
        return new RetentionGuardSnapshot(schedule, trustedTime, ledgerHealthy, rangeVerified,
                replacement, sourceLedgerHead, projectionWatermark, archiveDigest,
                guardVersion, holds, candidates);
    }

    public RetentionGuardSnapshot withLedgerHealthy(boolean replacement) {
        return new RetentionGuardSnapshot(schedule, trustedTime, replacement, rangeVerified,
                archiveReadBackVerified, sourceLedgerHead, projectionWatermark, archiveDigest,
                guardVersion, holds, candidates);
    }
}
