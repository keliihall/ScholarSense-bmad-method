package cn.edu.suda.scholarsense.ingestionquality.domain;

import static cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindingsTest.completeBindings;
import static cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindingsTest.digest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryWindowImpact.Category;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryWindowImpact.Input;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryImpactPreviewTest {
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00.000000Z");
    private static final UUID PREVIEW_ID = UUID.fromString("01914d3e-8a7b-7c1d-8abc-1234567890ab");

    @Test
    void previewClassifiesExactMicrosecondBoundariesWithoutAuthorizingAnything() {
        RecoveryImpactPreview preview = RecoveryImpactPreview.create(
                PREVIEW_ID, 1, bindings(), QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(
                        input("RULE_C@1.0.0", '3', NOW.plus(60, ChronoUnit.MINUTES)),
                        input("RULE_A@1.0.0", '1', NOW.minus(1, ChronoUnit.MICROS)),
                        input("RULE_B@1.0.0", '2', NOW),
                        input("RULE_B@1.0.1", '4', NOW.plus(60, ChronoUnit.MINUTES)
                                .minus(1, ChronoUnit.MICROS))),
                digest('9'));

        assertEquals(List.of(
                Category.ALREADY_EXPIRED_HISTORY_ONLY,
                Category.EXPECTED_TO_EXPIRE_BEFORE_OBSERVATION_COMPLETES,
                Category.EXPECTED_TO_EXPIRE_BEFORE_OBSERVATION_COMPLETES,
                Category.CURRENTLY_POTENTIALLY_ACTIONABLE),
                preview.ruleVersionWindows().stream().map(RecoveryWindowImpact::category).toList());
        assertEquals(NOW.plus(60, ChronoUnit.MINUTES),
                preview.expectedObservationCompletesAt());
        assertEquals("PT60M", preview.observationDurationWireValue());
        assertFalse(preview.authorizesExecution());
        assertEquals("2.5c", preview.finalActionabilityOwnerStory());
        assertEquals("invalidate-and-require-explicit-repreview", preview.driftHandling());
    }

    @Test
    void dailyBatchUsesAFullDayAndOutputIsUniquelyGroupedSortedAndImmutable() {
        ArrayList<Input> mutable = new ArrayList<>(List.of(
                input("RULE_Z@7", '2', NOW.plus(2, ChronoUnit.DAYS)),
                input("RULE_A@1", '1', NOW.plus(2, ChronoUnit.DAYS))));

        RecoveryImpactPreview preview = RecoveryImpactPreview.create(
                PREVIEW_ID, 1, bindings(), QualityRecoverySourceClass.DAILY_BATCH,
                RecoveryVersionBindingsTest.policy(), NOW, mutable, digest('8'));
        mutable.clear();

        assertEquals(NOW.plus(1, ChronoUnit.DAYS), preview.expectedObservationCompletesAt());
        assertEquals("P1D", preview.observationDurationWireValue());
        assertEquals(List.of("RULE_A@1", "RULE_Z@7"), preview.ruleVersionWindows().stream()
                .map(RecoveryWindowImpact::ruleVersion).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> preview.ruleVersionWindows().clear());

        Input repeated = input("RULE_A@1", '1', NOW.plus(2, ChronoUnit.DAYS));
        assertThrows(IllegalArgumentException.class, () -> RecoveryImpactPreview.create(
                PREVIEW_ID, 1, bindings(), QualityRecoverySourceClass.DAILY_BATCH,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(repeated, repeated), digest('8')));
    }

    @Test
    void exactInputSnapshotDriftInvalidatesPreviewAndRequiresExplicitRepreview() {
        RecoveryVersionBindings original = bindings();
        RecoveryImpactPreview preview = RecoveryImpactPreview.create(
                PREVIEW_ID, 1, original, QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(input("RULE_A@1", '1', NOW)), digest('8'));
        RecoveryVersionBindings watermarkDrift = new RecoveryVersionBindings(
                completeBindings(), 11, 12, 13, 4,
                digest('d'), digest('e'), digest('0'));

        assertTrue(preview.matchesExactInputs(
                original, QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(input("RULE_A@1", '1', NOW))));
        assertFalse(preview.matchesExactInputs(
                watermarkDrift, QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(input("RULE_A@1", '1', NOW))));
        assertFalse(preview.matchesExactInputs(
                original, QualityRecoverySourceClass.DAILY_BATCH,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(input("RULE_A@1", '1', NOW))));
        assertFalse(preview.matchesExactInputs(
                original, QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW.plus(1, ChronoUnit.MICROS),
                List.of(input("RULE_A@1", '1', NOW))));
        assertFalse(preview.matchesExactInputs(
                original, QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(input("RULE_A@1", '1', NOW.plus(1, ChronoUnit.MICROS)))));
        assertFalse(preview.matchesExactInputs(
                original, QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(input("RULE_A@1", '2', NOW))));
    }

    @Test
    void nonMicrosecondTimesEmptyWindowsAndMalformedRuleReferencesFailClosed() {
        Instant nanos = NOW.plusNanos(1);
        assertThrows(IllegalArgumentException.class, () -> RecoveryImpactPreview.create(
                PREVIEW_ID, 1, bindings(), QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), nanos,
                List.of(input("RULE_A@1", '1', NOW)), digest('8')));
        assertThrows(IllegalArgumentException.class, () -> RecoveryImpactPreview.create(
                PREVIEW_ID, 1, bindings(), QualityRecoverySourceClass.STREAMING,
                RecoveryVersionBindingsTest.policy(), NOW,
                List.of(), digest('8')));
        assertThrows(IllegalArgumentException.class,
                () -> new Input("student-123", digest('1'), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new Input("RULE_A@1", digest('1'), nanos));
    }

    @Test
    void story25bPreviewContainsNoRecoveryCompletionDecision() {
        assertTrue(List.of(RecoveryImpactPreview.class.getRecordComponents()).stream()
                .noneMatch(component -> component.getName().equals("recoveryCompletedAt")));
    }

    private static RecoveryVersionBindings bindings() {
        return RecoveryVersionBindingsTest.bindings(completeBindings());
    }

    private static Input input(String ruleVersion, char digestMarker, Instant latestActionableAt) {
        return new Input(ruleVersion, digest(digestMarker), latestActionableAt);
    }

}
