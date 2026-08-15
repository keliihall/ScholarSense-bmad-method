package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationEvidenceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class JdbcRecoveryObservationWorkTest {
    private static final UUID JOB_ID = UUID.fromString(
            "018f34c0-9b80-7a11-8abc-0123456789ab");
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void claimUsesPersistedEvidenceStatusesInsteadOfInventingPassedEvidence() {
        String payload = """
                {
                  "jobId":"018f34c0-9b80-7a11-8abc-0123456789ab",
                  "leaseGeneration":1,
                  "attemptNumber":1,
                  "recoveryId":"018f34c0-9b81-7a11-8abc-0123456789ab",
                  "generation":1,
                  "sourceId":"SRC-P0-CAMPUS-ACCESS-001",
                  "dependencyId":"DEP-P0-CAMPUS-ACCESS-001",
                  "sourceClass":"streaming",
                  "recoveringStartedAt":"2026-08-14T00:00:00Z",
                  "policyVersion":"QRP-1.0.0",
                  "policyDigest":"%s",
                  "memberSetDigest":"%s",
                  "watermarksDigest":"%s",
                  "allAffectedEligibilitiesRecovering":true,
                  "currentFence":{
                    "policyVersion":"QRP-1.0.0",
                    "policyDigest":"sha256:%s",
                    "memberSetDigest":"sha256:%s",
                    "watermarksDigest":"sha256:%s"
                  },
                  "evidence":{
                    "requiredMembers":"unknown",
                    "reconciliation":"unavailable",
                    "sample":"verified-failed",
                    "sloFreshness":"passed"
                  },
                  "facts":[]
                }
                """.formatted(DIGEST, DIGEST, DIGEST,
                        "b".repeat(64), "c".repeat(64), "d".repeat(64));
        JdbcRecoveryObservationWork work = new JdbcRecoveryObservationWork(
                new StaticJsonJdbcTemplate(payload), new ObjectMapper());

        var claim = work.claim(JOB_ID, DIGEST,
                Instant.parse("2026-08-14T01:00:00Z"), Duration.ofSeconds(120));
        var evidence = claim.observation().evidence();

        assertEquals(RecoveryObservationEvidenceStatus.UNKNOWN, evidence.requiredMembers());
        assertEquals(RecoveryObservationEvidenceStatus.UNAVAILABLE, evidence.reconciliation());
        assertEquals(RecoveryObservationEvidenceStatus.VERIFIED_FAILED, evidence.sample());
        assertEquals(RecoveryObservationEvidenceStatus.PASSED, evidence.sloFreshness());
        assertEquals("sha256:" + "b".repeat(64), claim.currentFence().policyDigest());
        assertEquals("sha256:" + "c".repeat(64), claim.currentFence().memberSetDigest());
        assertEquals("sha256:" + "d".repeat(64), claim.currentFence().watermarksDigest());
    }

    private static final class StaticJsonJdbcTemplate extends JdbcTemplate {
        private final String payload;

        private StaticJsonJdbcTemplate(String payload) {
            this.payload = payload;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(payload);
        }
    }
}
