package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Current authenticated actor facts rebuilt server-side for every command. */
public record QualityRecoveryCommandActor(
        String sessionPseudonym,
        String actorPseudonym,
        UUID accountId,
        String naturalPersonPrincipalDigest,
        String naturalPersonBindingSetDigest,
        Set<String> roleIds,
        long sessionVersion,
        Instant sessionExpiresAt,
        String identityProfileVersion) {
    public QualityRecoveryCommandActor {
        roleIds = Set.copyOf(roleIds);
        if (sessionPseudonym == null || sessionPseudonym.isBlank()
                || actorPseudonym == null || actorPseudonym.isBlank()
                || accountId == null || accountId.version() != 7 || accountId.variant() != 2
                || naturalPersonPrincipalDigest == null
                || !naturalPersonPrincipalDigest.matches("sha256:[0-9a-f]{64}")
                || naturalPersonBindingSetDigest == null
                || !naturalPersonBindingSetDigest.matches("sha256:[0-9a-f]{64}")
                || sessionVersion < 1 || sessionExpiresAt == null
                || identityProfileVersion == null || identityProfileVersion.isBlank()) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RECOVERY_ACTOR_INVALID");
        }
    }
}
