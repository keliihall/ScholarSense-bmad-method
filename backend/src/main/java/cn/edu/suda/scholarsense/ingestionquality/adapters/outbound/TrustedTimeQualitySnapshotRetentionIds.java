package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionAttemptIds;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionIdPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.Objects;

/** Generates all owner attempt IDs from one trusted retention invocation timestamp. */
public final class TrustedTimeQualitySnapshotRetentionIds
        implements QualitySnapshotRetentionIdPort {
    private final TrustedTimeSource time;

    public TrustedTimeQualitySnapshotRetentionIds(TrustedTimeSource time) {
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public QualitySnapshotRetentionAttemptIds nextAttempt() {
        Instant now = time.now().instant();
        return new QualitySnapshotRetentionAttemptIds(
                CatalogUuidV7.generate(now),
                CatalogUuidV7.generate(now));
    }
}
