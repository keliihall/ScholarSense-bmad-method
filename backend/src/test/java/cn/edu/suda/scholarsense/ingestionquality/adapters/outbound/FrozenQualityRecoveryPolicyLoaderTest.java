package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoverySourceClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class FrozenQualityRecoveryPolicyLoaderTest {
    private static final Path RELATIVE = Path.of(
            "ingestion-quality", "quality-recovery", "quality-recovery-policy-1.0.0.json");

    @Test
    void loadsOnlyTheExactApprovedRecoveryPolicy() {
        var policy = FrozenQualityRecoveryPolicyLoader.load(
                Path.of("..", "contracts"), new ObjectMapper());

        assertEquals("QRP-1.0.0", policy.policyVersion());
        assertEquals(
                "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366",
                policy.contractDigest());
        assertEquals(3, policy.ruleFor(QualityRecoverySourceClass.STREAMING)
                .consecutivePassedBatches());
        assertEquals(Duration.ofMinutes(60),
                policy.ruleFor(QualityRecoverySourceClass.STREAMING).observationDuration());
        assertEquals(2, policy.ruleFor(QualityRecoverySourceClass.DAILY_BATCH)
                .consecutivePassedBatches());
        assertEquals(Duration.ofHours(24),
                policy.ruleFor(QualityRecoverySourceClass.DAILY_BATCH).observationDuration());
        assertEquals(Duration.ofDays(90), policy.backfill().lookback());
        assertTrue(policy.backfill().trustedNowRequired());
        assertEquals(0, policy.reconciliation().expectedMismatchCount());
        assertEquals(100, policy.sampling().minimumSubjectWindows());
        assertTrue(policy.sampling().allIfPopulationFewer());
        assertEquals(0, policy.sampling().expectedMismatchCount());
        assertThrows(UnsupportedOperationException.class, () ->
                policy.sourceClasses().put(
                        QualityRecoverySourceClass.STREAMING,
                        policy.ruleFor(QualityRecoverySourceClass.STREAMING)));
    }

    @Test
    void rejectsAnyRawPolicyMutation(@TempDir Path contractRoot) throws Exception {
        Path target = contractRoot.resolve(RELATIVE);
        Files.createDirectories(target.getParent());
        String approved = Files.readString(Path.of("..", "contracts").resolve(RELATIVE));
        Files.writeString(target, approved.replace(
                "\"consecutivePassedBatches\": 3",
                "\"consecutivePassedBatches\": 4"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                FrozenQualityRecoveryPolicyLoader.load(contractRoot, new ObjectMapper()));

        assertEquals("QUALITY_RECOVERY_POLICY_INVALID", error.getMessage());
    }

    @Test
    void rejectsUnknownPolicyArtifactShapeEvenWhenParsingHelpersAreExercised() {
        var policy = FrozenQualityRecoveryPolicyLoader.load(
                Path.of("..", "contracts"), new ObjectMapper());

        assertEquals(
                Set.of("eventId", "occurredAt", "watermark", "batchId"),
                policy.batchQualification().forbiddenOrderingFields());
        assertEquals(
                "max(lastKnownGoodWatermark,trustedNow-minus-P90D)",
                policy.backfill().startExpression());
        assertEquals("all-current-required-members-eligible",
                policy.qualityGate().requiredDependencies());
        assertEquals(false, policy.impactPreview().authorizesExecution());
        assertEquals(false, policy.failureFallback().createsBusinessObjects());
    }
}
