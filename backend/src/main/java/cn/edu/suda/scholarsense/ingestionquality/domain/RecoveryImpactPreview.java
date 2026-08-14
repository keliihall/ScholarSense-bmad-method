package cn.edu.suda.scholarsense.ingestionquality.domain;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryWindowImpact.Input;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryVersionBindings.ResolvedPolicyEvidence;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable recovery impact snapshot. This value is evidence and can never authorize execution. */
public record RecoveryImpactPreview(
        UUID previewId,
        long previewVersion,
        RecoveryVersionBindings versionBindings,
        ResolvedPolicyEvidence policyEvidence,
        QualityRecoverySourceClass sourceClass,
        Instant trustedServerNow,
        Duration observationDuration,
        Instant expectedObservationCompletesAt,
        List<RecoveryWindowImpact> ruleVersionWindows,
        String previewDigest,
        Instant generatedAt) {
    private static final int MAX_WINDOWS = 10_000;

    public RecoveryImpactPreview {
        previewId = uuidV7(previewId);
        previewVersion = RecoveryVersionBindings.safePositive(previewVersion);
        versionBindings = Objects.requireNonNull(versionBindings);
        policyEvidence = Objects.requireNonNull(policyEvidence);
        sourceClass = Objects.requireNonNull(sourceClass);
        if (!policyEvidence.matches(versionBindings, sourceClass)
                || !policyEvidence.observationDuration().equals(observationDuration)) {
            throw invalid();
        }
        trustedServerNow = RecoveryWindowImpact.microsecond(trustedServerNow);
        observationDuration = Objects.requireNonNull(observationDuration);
        if (observationDuration.isZero() || observationDuration.isNegative()) throw invalid();
        expectedObservationCompletesAt = RecoveryWindowImpact.microsecond(
                expectedObservationCompletesAt);
        Instant exactCompletion;
        try {
            exactCompletion = trustedServerNow.plus(observationDuration);
        } catch (DateTimeException | ArithmeticException error) {
            throw invalid();
        }
        if (!exactCompletion.equals(expectedObservationCompletesAt)) throw invalid();
        List<RecoveryWindowImpact> copy = List.copyOf(Objects.requireNonNull(ruleVersionWindows))
                .stream()
                .sorted(Comparator.comparing(RecoveryWindowImpact::ruleVersion)
                        .thenComparing(RecoveryWindowImpact::windowDigest))
                .toList();
        if (copy.isEmpty() || copy.size() > MAX_WINDOWS
                || copy.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        HashSet<String> keys = new HashSet<>();
        for (RecoveryWindowImpact window : copy) {
            String key = window.ruleVersion() + '\0' + window.windowDigest();
            if (!keys.add(key)) throw invalid();
        }
        ruleVersionWindows = copy;
        previewDigest = RecoveryVersionBindings.digest(previewDigest);
        generatedAt = RecoveryWindowImpact.microsecond(generatedAt);
    }

    public static RecoveryImpactPreview create(
            UUID previewId,
            long previewVersion,
            RecoveryVersionBindings versionBindings,
            QualityRecoverySourceClass sourceClass,
            QualityRecoveryPolicy policy,
            Instant trustedServerNow,
            List<Input> windows,
            String previewDigest) {
        Objects.requireNonNull(sourceClass);
        ResolvedPolicyEvidence resolved = ResolvedPolicyEvidence.from(
                policy, sourceClass, versionBindings);
        Instant now = RecoveryWindowImpact.microsecond(trustedServerNow);
        Duration duration = resolved.observationDuration();
        Instant completesAt;
        try {
            completesAt = now.plus(duration);
        } catch (DateTimeException | ArithmeticException error) {
            throw invalid();
        }
        List<RecoveryWindowImpact> classified = List.copyOf(Objects.requireNonNull(windows))
                .stream()
                .map(input -> RecoveryWindowImpact.classify(input, now, completesAt))
                .toList();
        return new RecoveryImpactPreview(
                previewId, previewVersion, versionBindings, resolved, sourceClass, now,
                duration, completesAt, classified, previewDigest, now);
    }

    /** Exact equality is the drift fence; callers must create a new preview after any mismatch. */
    public boolean matchesExactInputs(
            RecoveryVersionBindings currentBindings,
            QualityRecoverySourceClass currentSourceClass,
            QualityRecoveryPolicy currentPolicy,
            Instant currentTrustedServerNow,
            List<Input> currentWindows) {
        try {
            ResolvedPolicyEvidence currentResolved = ResolvedPolicyEvidence.from(
                    currentPolicy, currentSourceClass, currentBindings);
            if (!versionBindings.equals(currentBindings)
                    || !policyEvidence.equals(currentResolved)
                    || !trustedServerNow.equals(currentTrustedServerNow)) {
                return false;
            }
            Instant currentCompletesAt = RecoveryWindowImpact.microsecond(
                    currentTrustedServerNow).plus(currentResolved.observationDuration());
            List<RecoveryWindowImpact> currentClassified = List.copyOf(
                            Objects.requireNonNull(currentWindows))
                    .stream()
                    .map(input -> RecoveryWindowImpact.classify(
                            input, currentTrustedServerNow, currentCompletesAt))
                    .sorted(Comparator.comparing(RecoveryWindowImpact::ruleVersion)
                            .thenComparing(RecoveryWindowImpact::windowDigest))
                    .toList();
            return ruleVersionWindows.equals(currentClassified);
        } catch (RuntimeException invalidCurrentInput) {
            return false;
        }
    }

    public String schemaVersion() {
        return "QUALITY-RECOVERY-IMPACT-PREVIEW-1.0.0";
    }

    public String targetState() {
        return "recovering";
    }

    public String qualityRecoveryPolicyVersion() {
        return versionBindings.binding(
                RecoveryVersionBindings.Authority.QUALITY_RECOVERY_POLICY).version();
    }

    public String qualityRecoveryPolicyDigest() {
        return versionBindings.binding(
                RecoveryVersionBindings.Authority.QUALITY_RECOVERY_POLICY).digest();
    }

    public String observationDurationWireValue() {
        if (observationDuration.toNanosPart() == 0
                && observationDuration.toSecondsPart() == 0
                && observationDuration.toMinutesPart() == 0
                && observationDuration.toHoursPart() == 0) {
            return "P%dD".formatted(observationDuration.toDays());
        }
        if (observationDuration.toNanosPart() == 0
                && observationDuration.toSecondsPart() == 0) {
            return "PT%dM".formatted(observationDuration.toMinutes());
        }
        return observationDuration.toString();
    }

    public boolean authorizesExecution() {
        return false;
    }

    public String finalActionabilityOwnerStory() {
        return "2.5c";
    }

    public String driftHandling() {
        return "invalidate-and-require-explicit-repreview";
    }

    public String canonicalizationProfile() {
        return "SCHOLARSENSE-CANONICAL-JSON-1.0.0";
    }

    public String runtimeEvidenceClaim() {
        return "contract-only";
    }

    private static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_IMPACT_PREVIEW_INVALID");
    }
}
