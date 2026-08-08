package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.time.Instant;
import java.util.Objects;

public record EffectiveInterval(Instant effectiveFrom, Instant effectiveTo) {
    public EffectiveInterval {
        Objects.requireNonNull(effectiveFrom);
        if (effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        }
    }

    public static EffectiveInterval of(Instant effectiveFrom, Instant effectiveTo) {
        return new EffectiveInterval(effectiveFrom, effectiveTo);
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant);
        return !instant.isBefore(effectiveFrom)
                && (effectiveTo == null || instant.isBefore(effectiveTo));
    }

    public boolean overlaps(EffectiveInterval other) {
        Objects.requireNonNull(other);
        boolean startsBeforeOtherEnds = other.effectiveTo == null
                || effectiveFrom.isBefore(other.effectiveTo);
        boolean otherStartsBeforeThisEnds = effectiveTo == null
                || other.effectiveFrom.isBefore(effectiveTo);
        return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }
}
