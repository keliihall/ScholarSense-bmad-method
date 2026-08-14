package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindings.Authority;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindings.VersionDigestBinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryVersionBindingsTest {

    @Test
    void completeBindingsAreSortedCopiedAndExact() {
        List<VersionDigestBinding> mutable = new ArrayList<>(completeBindings());
        Collections.reverse(mutable);

        RecoveryVersionBindings result = new RecoveryVersionBindings(
                mutable, 11, 12, 13, 4, digest('d'), digest('e'), digest('f'));
        mutable.clear();

        assertEquals(Arrays.asList(Authority.values()), result.versions().stream()
                .map(VersionDigestBinding::authority).toList());
        assertEquals(Authority.values().length, result.versions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.versions().add(binding(Authority.RULE_CATALOG)));
        assertEquals("QRP-1.0.0",
                result.binding(Authority.QUALITY_RECOVERY_POLICY).version());
    }

    @Test
    void missingDuplicateUnknownOrInexactBindingsFailClosed() {
        List<VersionDigestBinding> missing = new ArrayList<>(completeBindings());
        missing.removeLast();
        assertThrows(IllegalArgumentException.class, () -> bindings(missing));

        List<VersionDigestBinding> duplicate = new ArrayList<>(completeBindings());
        duplicate.set(duplicate.size() - 1, binding(Authority.QUALITY_RECOVERY_POLICY));
        assertThrows(IllegalArgumentException.class, () -> bindings(duplicate));

        assertThrows(IllegalArgumentException.class, () -> new VersionDigestBinding(
                Authority.QUALITY_RECOVERY_POLICY, "QRP-1.0.1", digest('a')));
        assertThrows(IllegalArgumentException.class, () -> new VersionDigestBinding(
                Authority.SOURCE_SCHEMA, "uncontrolled", digest('a')));
        assertThrows(IllegalArgumentException.class, () -> new VersionDigestBinding(
                Authority.DEPENDENCY, "0", digest('a')));
        assertThrows(IllegalArgumentException.class, () -> new VersionDigestBinding(
                Authority.RULE_CATALOG, "RC-1.0.0", "not-a-digest"));
    }

    @Test
    void anyVersionOrWatermarkDriftProducesAUnequalBindingSnapshot() {
        RecoveryVersionBindings baseline = bindings(completeBindings());
        RecoveryVersionBindings watermarkDrift = new RecoveryVersionBindings(
                completeBindings(), 11, 12, 13, 4,
                digest('d'), digest('e'), digest('0'));
        List<VersionDigestBinding> changed = new ArrayList<>(completeBindings());
        changed.set(0, new VersionDigestBinding(
                Authority.QUALITY_RECOVERY_POLICY, "QRP-1.0.0", digest('c')));
        RecoveryVersionBindings policyDrift = bindings(changed);

        assertNotEquals(baseline, watermarkDrift);
        assertNotEquals(baseline, policyDrift);
    }

    static RecoveryVersionBindings bindings(List<VersionDigestBinding> values) {
        return new RecoveryVersionBindings(
                values, 11, 12, 13, 4, digest('d'), digest('e'), digest('f'));
    }

    static List<VersionDigestBinding> completeBindings() {
        return Arrays.stream(Authority.values()).map(RecoveryVersionBindingsTest::binding).toList();
    }

    private static VersionDigestBinding binding(Authority authority) {
        String version = switch (authority) {
            case QUALITY_RECOVERY_POLICY -> "QRP-1.0.0";
            case HIGH_RISK_ACTION_POLICY -> "HRAP-1.0.0";
            case HIGH_RISK_ACTION_MATRIX -> "HRAM-1.0.0";
            case ROLE_FIELD_POLICY -> "RFP-1.0.0";
            case DATA_CONTRACT_CATALOG -> "DCC-1.1.0";
            case QUALITY_GATE -> "QG-1.0.0";
            case QUALITY_METRIC_DECISION_PROFILE -> "QMDP-1.0.0";
            case QUALITY_SNAPSHOT_HASH_PROFILE -> "QSHM-1.0.0";
            case RULE_DEPENDENCY_REGISTRY -> "RULE-DEPENDENCY-REGISTRY-1.0.0";
            case RULE_CATALOG -> "RC-1.0.0";
            case SOURCE_SCHEMA -> "CAMPUS-ACCESS-1.0.0";
            case DEPENDENCY -> "7";
        };
        char marker = Character.forDigit(authority.ordinal() % 16, 16);
        String bindingDigest = authority == Authority.QUALITY_RECOVERY_POLICY
                ? QualityRecoveryPolicy.RAW_DIGEST : digest(marker);
        return new VersionDigestBinding(authority, version, bindingDigest);
    }

    static QualityRecoveryPolicy policy() {
        return new QualityRecoveryPolicy(
                QualityRecoveryPolicy.VERSION,
                QualityRecoveryPolicy.RAW_DIGEST,
                List.of("DEC-012", "G-03", "AD-5", "AD-23", "DCC-1.1.0", "QG-1.0.0",
                        "RC-1.0.0"),
                new QualityRecoveryPolicy.SourceClassBinding(
                        "ingestion-quality", "approved-explicit-source-class-registry", "reject"),
                java.util.Map.of(
                        QualityRecoverySourceClass.STREAMING,
                        new QualityRecoveryPolicy.SourceClassRule(
                                3, java.time.Duration.ofMinutes(60)),
                        QualityRecoverySourceClass.DAILY_BATCH,
                        new QualityRecoveryPolicy.SourceClassRule(
                                2, java.time.Duration.ofHours(24))),
                new QualityRecoveryPolicy.BatchQualification(
                        "assessed-passed-then-exact-published",
                        List.of("sourceId", "sourceVersionOrdinal", "lineageRevision"),
                        java.util.Set.of("eventId", "occurredAt", "watermark", "batchId"), true),
                new QualityRecoveryPolicy.Backfill(
                        java.time.Duration.ofDays(90),
                        "max(lastKnownGoodWatermark,trustedNow-minus-P90D)", true),
                new QualityRecoveryPolicy.Reconciliation("full", 0),
                new QualityRecoveryPolicy.Sampling("subject-window", true, 100, true, 0, true),
                new QualityRecoveryPolicy.QualityGate(
                        "QG-1.0.0", true, "use-approved-inclusive-operator",
                        "all-current-required-members-eligible"),
                new QualityRecoveryPolicy.ImpactPreview(
                        true,
                        List.of(
                                "already-expired-history-only",
                                "currently-potentially-actionable",
                                "expected-to-expire-before-observation-completes"),
                        "2.5c", false),
                new QualityRecoveryPolicy.FailureFallback(
                        "remain-fused", java.time.Duration.ofHours(24), false),
                "contract-only");
    }

    static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
