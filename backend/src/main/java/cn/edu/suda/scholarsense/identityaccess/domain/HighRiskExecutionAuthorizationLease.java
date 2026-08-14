package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable cross-owner execution authorization with late-confirmation semantics. */
public final class HighRiskExecutionAuthorizationLease {
    private final UUID leaseId;
    private final String leaseDigest;
    private final UUID executionJti;
    private final HighRiskExecutionToken token;
    private final String keyVersion;
    private final String signature;
    private final Instant issuedAt;
    private long leaseVersion;
    private HighRiskExecutionLeaseState state;
    private Instant reservedAt;
    private Instant ownerCommittedAt;
    private Instant confirmedAt;
    private String ownerCommitId;
    private String ownerResultDigest;
    private UUID outboxEventId;

    private HighRiskExecutionAuthorizationLease(
            UUID leaseId,
            String leaseDigest,
            UUID executionJti,
            HighRiskExecutionToken token,
            String keyVersion,
            String signature) {
        HighRiskApprovalBinding.requireUuidV7(leaseId);
        HighRiskApprovalBinding.requireDigest(leaseDigest);
        HighRiskApprovalBinding.requireUuidV7(executionJti);
        if (token == null || keyVersion == null
                || !keyVersion.matches("[A-Za-z0-9._-]{1,64}")
                || signature == null || !signature.matches("[A-Za-z0-9_-]{43,512}")) {
            throw HighRiskApprovalBinding.invalid();
        }
        this.leaseId = leaseId;
        this.leaseDigest = leaseDigest;
        this.executionJti = executionJti;
        this.token = token;
        this.keyVersion = keyVersion;
        this.signature = signature;
        this.issuedAt = token.issuedAt();
        this.leaseVersion = 1;
        this.state = HighRiskExecutionLeaseState.ISSUED;
    }

    public static HighRiskExecutionAuthorizationLease issued(
            UUID leaseId,
            String leaseDigest,
            UUID executionJti,
            HighRiskExecutionToken token,
            String keyVersion,
            String signature) {
        return new HighRiskExecutionAuthorizationLease(
                leaseId, leaseDigest, executionJti, token, keyVersion, signature);
    }

    /** Rehydrates the durable lease while preserving its monotonic state and fences. */
    public static HighRiskExecutionAuthorizationLease restore(
            UUID leaseId,
            long leaseVersion,
            String leaseDigest,
            UUID executionJti,
            HighRiskExecutionToken token,
            String keyVersion,
            String signature,
            HighRiskExecutionLeaseState state,
            Instant reservedAt,
            Instant ownerCommittedAt,
            Instant confirmedAt,
            String ownerCommitId,
            String ownerResultDigest,
            UUID outboxEventId) {
        HighRiskExecutionAuthorizationLease value = issued(
                leaseId, leaseDigest, executionJti, token, keyVersion, signature);
        if (leaseVersion < 1 || state == null
                || state == HighRiskExecutionLeaseState.ISSUED && reservedAt != null
                || state == HighRiskExecutionLeaseState.RESERVED && reservedAt == null
                || state == HighRiskExecutionLeaseState.EXECUTED
                        && (reservedAt == null || ownerCommittedAt == null || confirmedAt == null
                                || ownerCommitId == null || ownerResultDigest == null
                                || outboxEventId == null)
                || state != HighRiskExecutionLeaseState.EXECUTED
                        && (ownerCommittedAt != null || confirmedAt != null
                                || ownerCommitId != null || ownerResultDigest != null
                                || outboxEventId != null)) {
            throw HighRiskApprovalBinding.invalid();
        }
        if (reservedAt != null && (reservedAt.getNano() % 1_000 != 0
                || reservedAt.isBefore(token.issuedAt()))) {
            throw HighRiskApprovalBinding.invalid();
        }
        if (ownerResultDigest != null) HighRiskApprovalBinding.requireDigest(ownerResultDigest);
        if (outboxEventId != null) HighRiskApprovalBinding.requireUuidV7(outboxEventId);
        value.leaseVersion = leaseVersion;
        value.state = state;
        value.reservedAt = reservedAt;
        value.ownerCommittedAt = ownerCommittedAt;
        value.confirmedAt = confirmedAt;
        value.ownerCommitId = ownerCommitId;
        value.ownerResultDigest = ownerResultDigest;
        value.outboxEventId = outboxEventId;
        return value;
    }

    public void reserve(Instant trustedNow) {
        requireActiveAt(trustedNow);
        if (state != HighRiskExecutionLeaseState.ISSUED) throw invalidState();
        state = HighRiskExecutionLeaseState.RESERVED;
        reservedAt = trustedNow;
        leaseVersion++;
    }

    public void confirmOwnerCommit(
            String commitId,
            Instant committedAt,
            String resultDigest,
            UUID eventId,
            Instant confirmed) {
        if ((state != HighRiskExecutionLeaseState.RESERVED
                    && state != HighRiskExecutionLeaseState.EXPIRED)
                || commitId == null || !commitId.matches("[A-Za-z0-9._:-]{16,128}")
                || committedAt == null || confirmed == null
                || committedAt.getNano() % 1_000 != 0 || confirmed.getNano() % 1_000 != 0
                || !committedAt.isBefore(token.authorizedUntil())
                || reservedAt == null || committedAt.isBefore(reservedAt)
                || confirmed.isBefore(committedAt)) {
            throw invalidState();
        }
        HighRiskApprovalBinding.requireDigest(resultDigest);
        HighRiskApprovalBinding.requireUuidV7(eventId);
        state = HighRiskExecutionLeaseState.EXECUTED;
        ownerCommitId = commitId;
        ownerCommittedAt = committedAt;
        ownerResultDigest = resultDigest;
        outboxEventId = eventId;
        confirmedAt = confirmed;
        leaseVersion++;
    }

    public void expire(Instant trustedNow) {
        if ((state != HighRiskExecutionLeaseState.ISSUED
                    && state != HighRiskExecutionLeaseState.RESERVED)
                || trustedNow == null || trustedNow.getNano() % 1_000 != 0
                || trustedNow.isBefore(token.authorizedUntil())) throw invalidState();
        state = HighRiskExecutionLeaseState.EXPIRED;
        leaseVersion++;
    }

    public void cancel(Instant trustedNow) {
        requireActiveAt(trustedNow);
        if (state != HighRiskExecutionLeaseState.ISSUED
                && state != HighRiskExecutionLeaseState.RESERVED) throw invalidState();
        state = HighRiskExecutionLeaseState.CANCELLED;
        leaseVersion++;
    }

    private void requireActiveAt(Instant trustedNow) {
        if (trustedNow == null || trustedNow.getNano() % 1_000 != 0
                || trustedNow.isBefore(issuedAt)
                || !trustedNow.isBefore(token.authorizedUntil())) throw invalidState();
    }

    private static IllegalStateException invalidState() {
        return new IllegalStateException("HIGH_RISK_EXECUTION_LEASE_STATE_INVALID");
    }

    public UUID leaseId() { return leaseId; }
    public long leaseVersion() { return leaseVersion; }
    public String leaseDigest() { return leaseDigest; }
    public UUID executionJti() { return executionJti; }
    public HighRiskExecutionLeaseState state() { return state; }
    public HighRiskExecutionToken token() { return token; }
    public String keyVersion() { return keyVersion; }
    public String signature() { return signature; }
    public Instant reservedAt() { return reservedAt; }
    public Instant ownerCommittedAt() { return ownerCommittedAt; }
    public Instant confirmedAt() { return confirmedAt; }
    public String ownerCommitId() { return ownerCommitId; }
    public String ownerResultDigest() { return ownerResultDigest; }
    public UUID outboxEventId() { return outboxEventId; }

    public String canonicalUnsigned() {
        return String.join("\n",
                "HIGH-RISK-EXECUTION-LEASE-1.0.0", leaseId.toString(),
                executionJti.toString(), token.canonicalUnsigned(), token.keyVersion(),
                token.signature(), issuedAt.toString(), keyVersion);
    }
}
