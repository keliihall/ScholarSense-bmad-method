package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotCanonicalizer;
import java.util.Objects;

/** Non-forgeable owner-write value produced only after policy and hash validation. */
public final class VerifiedQualitySnapshot {
    private final QualitySnapshot value;

    private VerifiedQualitySnapshot(QualitySnapshot value) {
        this.value = Objects.requireNonNull(value);
    }

    static VerifiedQualitySnapshot verify(
            QualitySnapshot value,
            QualitySnapshotCanonicalizer canonicalizer) {
        Objects.requireNonNull(canonicalizer).verify(value);
        return new VerifiedQualitySnapshot(value);
    }

    public QualitySnapshot value() {
        return value;
    }
}
