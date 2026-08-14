package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoverySourceClass;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationReadinessEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class FrozenRecoverySourceClassRegistryLoaderTest {
    private static final Path RELATIVE = Path.of(
            "ingestion-quality", "quality-recovery",
            "recovery-source-class-registry-1.0.0.json");

    @Test
    void loadsTheApprovedNineStreamingTwoDailyBindingsAndDeniesUnknownSources() {
        var registry = FrozenRecoverySourceClassRegistryLoader.load(
                Path.of("..", "contracts"), new ObjectMapper());

        assertEquals("QRSCR-1.0.0", registry.registryVersion());
        assertEquals(
                "sha256:35df983b04d3f80482d1bd21776137712f60f934d79480e47fc002fb2148bb3d",
                registry.contractDigest());
        assertEquals(
                "sha256:dfa05c804f27a4c92edb3c6ed7253c3bdb3fa7d3c6d77be7121a44c716b7aed5",
                registry.bindingSetDigest());
        assertEquals("AUTH-2026-08-12-QRSCR-001", registry.approvalRef());
        assertEquals("Hei", registry.approvedBy());
        assertEquals(OffsetDateTime.parse("2026-08-12T19:12:19+08:00"),
                registry.effectiveAt());
        assertEquals(11, registry.bindings().size());
        assertEquals(9, registry.bindings().values().stream()
                .filter(QualityRecoverySourceClass.STREAMING::equals).count());
        assertEquals(2, registry.bindings().values().stream()
                .filter(QualityRecoverySourceClass.DAILY_BATCH::equals).count());
        assertEquals(QualityRecoverySourceClass.STREAMING,
                registry.requireClass("SRC-P0-CARD-001"));
        assertEquals(QualityRecoverySourceClass.DAILY_BATCH,
                registry.requireClass("SRC-P1-ACADEMIC-001"));
        assertEquals("QUALITY_RECOVERY_SOURCE_CLASS_UNKNOWN", assertThrows(
                IllegalArgumentException.class,
                () -> registry.requireClass("SRC-NOT-APPROVED-001")).getMessage());
        assertThrows(UnsupportedOperationException.class, () ->
                registry.bindings().put(
                        "SRC-NOT-APPROVED-001", QualityRecoverySourceClass.STREAMING));
    }

    @Test
    void rejectsAnyRawRegistryMutation(@TempDir Path contractRoot) throws Exception {
        Path target = contractRoot.resolve(RELATIVE);
        Files.createDirectories(target.getParent());
        String approved = Files.readString(Path.of("..", "contracts").resolve(RELATIVE));
        Files.writeString(target, approved.replace(
                "\"sourceClass\": \"dailyBatch\"",
                "\"sourceClass\": \"streaming\""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                FrozenRecoverySourceClassRegistryLoader.load(
                        contractRoot, new ObjectMapper()));

        assertEquals("QUALITY_RECOVERY_SOURCE_CLASS_REGISTRY_INVALID", error.getMessage());
    }

    @Test
    void persistedReadinessMustStillMatchTheFrozenPolicyAndApprovedSourceClass() {
        ObjectMapper json = new ObjectMapper();
        Path root = Path.of("..", "contracts");
        var policy = FrozenQualityRecoveryPolicyLoader.load(root, json);
        var registry = FrozenRecoverySourceClassRegistryLoader.load(root, json);
        var approved = readiness("streaming", 3, 90);

        approved.requireFrozenAuthority(policy, registry);
        assertEquals("QUALITY_RECOVERY_READINESS_EVIDENCE_INVALID", assertThrows(
                IllegalArgumentException.class,
                () -> readiness("dailyBatch", 3, 90)
                        .requireFrozenAuthority(policy, registry)).getMessage());
        assertEquals("QUALITY_RECOVERY_READINESS_EVIDENCE_INVALID", assertThrows(
                IllegalArgumentException.class,
                () -> readiness("streaming", 2, 90)
                        .requireFrozenAuthority(policy, registry)).getMessage());
        assertEquals("QUALITY_RECOVERY_READINESS_EVIDENCE_INVALID", assertThrows(
                IllegalArgumentException.class,
                () -> readiness("streaming", 3, 91)
                        .requireFrozenAuthority(policy, registry)).getMessage());
    }

    private static RecoveryValidationReadinessEvidence readiness(
            String sourceClass, int requiredBatches, int lookbackDays) {
        String digest = "sha256:" + "a".repeat(64);
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        return new RecoveryValidationReadinessEvidence(
                1, 1, 1, "SRC-P0-CARD-001", "DEP-P0-CARD-001",
                digest, digest, digest, sourceClass, "CARD-SLICE-1.0.0", digest,
                "1", digest,
                List.of(new RecoveryValidationReadinessEvidence.EligibilityBinding(
                        UUID.fromString("019ff5a0-0000-7000-8000-000000000106"),
                        "ACC-SAFE-001", "1.0.0", 2)), digest,
                new RecoveryValidationReadinessEvidence.QualityEvidence(
                        digest, digest, digest, true, "wm-source", "wm-dependency"),
                new RecoveryValidationReadinessEvidence.BatchEvidence(
                        digest, requiredBatches, requiredBatches),
                new RecoveryValidationReadinessEvidence.BackfillEvidence(
                        "wm-lkg", now, lookbackDays, "wm-start", "wm-complete",
                        digest, "succeeded"),
                true, List.of(), 0, 1, 0, now, digest);
    }
}
