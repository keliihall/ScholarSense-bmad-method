package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionReconciliation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** At-least-once relay from the IQ owner commit to identity-access reconciliation. */
public final class QualityRecoveryConfirmationRelayProcessor {
    private final QualityRecoveryConfirmationRelayPort outbox;
    private final HighRiskExecutionAuthorizationPort authorizations;
    private final RecoveryValidationTrustedTimePort time;

    public QualityRecoveryConfirmationRelayProcessor(
            QualityRecoveryConfirmationRelayPort outbox,
            HighRiskExecutionAuthorizationPort authorizations,
            RecoveryValidationTrustedTimePort time) {
        this.outbox = java.util.Objects.requireNonNull(outbox);
        this.authorizations = java.util.Objects.requireNonNull(authorizations);
        this.time = java.util.Objects.requireNonNull(time);
    }

    public int runBatch(int limit) {
        Instant now = java.util.Objects.requireNonNull(time.now());
        int delivered = 0;
        for (QualityRecoveryConfirmationClaim claim : outbox.claim(limit, now)) {
            try {
                authorizations.reconcile(new HighRiskExecutionReconciliation(
                        claim.leaseId(), claim.leaseVersion(), claim.leaseDigest(),
                        claim.executionJti(), claim.requestDigest(), claim.ownerCommitId(),
                        claim.ownerCommittedAt(), claim.ownerResultDigest(),
                        claim.outboxEventId(), reconciliationDigest(claim), claim.traceId()));
                if (!outbox.markDelivered(
                        claim.outboxEventId(), claim.payloadDigest(), time.now())) {
                    throw new IllegalStateException(
                            "INGESTION_QUALITY_RECOVERY_CONFIRMATION_ACK_CONFLICT");
                }
                delivered++;
            } catch (RuntimeException failure) {
                outbox.release(claim.outboxEventId(), claim.payloadDigest(),
                        errorCode(failure), time.now());
            }
        }
        return delivered;
    }

    private static String reconciliationDigest(QualityRecoveryConfirmationClaim value) {
        return digest(String.join("\n", value.leaseId().toString(),
                Long.toString(value.leaseVersion()), value.leaseDigest(),
                value.executionJti().toString(), value.requestDigest(), value.ownerCommitId(),
                value.ownerCommittedAt().toString(), value.ownerResultDigest(),
                value.outboxEventId().toString(), value.traceId()));
    }

    private static String errorCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("[A-Z0-9_]{3,96}")) return message;
        return "RECOVERY_CONFIRMATION_RELAY_UNAVAILABLE";
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
