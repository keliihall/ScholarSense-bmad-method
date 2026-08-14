package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact owner-local immutable snapshot evidence, including QSHM refs absent on the old wire event. */
public record QualityEligibilitySnapshotEvidence(
        UUID snapshotId,
        UUID batchId,
        String sourceId,
        QualityOverallResult result,
        BatchObservationWindow observationWindow,
        Instant cutoffAt,
        String watermark,
        String manifestDigest,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        String qmdpVersion,
        String qmdpDigest,
        String qualityGateVersion,
        String qualityGateDigest,
        String qshmVersion,
        String qshmDigest,
        UUID lineageId,
        Instant effectiveAt,
        String immutableHash,
        List<QualityFuseFormulaBoundaryEvidence> formulaEvidence) {

    public QualityEligibilitySnapshotEvidence {
        snapshotId = uuidV7(snapshotId);
        batchId = uuidV7(batchId);
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
            throw invalid();
        }
        result = Objects.requireNonNull(result);
        observationWindow = Objects.requireNonNull(observationWindow);
        cutoffAt = Objects.requireNonNull(cutoffAt);
        watermark = text(watermark);
        manifestDigest = digest(manifestDigest);
        sourceSchemaVersion = text(sourceSchemaVersion);
        sourceSchemaDigest = digest(sourceSchemaDigest);
        qmdpVersion = text(qmdpVersion);
        qmdpDigest = digest(qmdpDigest);
        qualityGateVersion = text(qualityGateVersion);
        qualityGateDigest = digest(qualityGateDigest);
        if (!"QSHM-1.0.0".equals(qshmVersion)) throw invalid();
        qshmDigest = digest(qshmDigest);
        lineageId = uuidV7(lineageId);
        effectiveAt = Objects.requireNonNull(effectiveAt);
        immutableHash = digest(immutableHash);
        formulaEvidence = List.copyOf(Objects.requireNonNull(formulaEvidence)).stream()
                .sorted(Comparator.comparing(QualityFuseFormulaBoundaryEvidence::formulaId))
                .toList();
        if (formulaEvidence.isEmpty()
                || new HashSet<>(formulaEvidence.stream()
                        .map(QualityFuseFormulaBoundaryEvidence::formulaId).toList()).size()
                    != formulaEvidence.size()) throw invalid();
    }

    public QualityEligibilitySnapshotEvidence withManifestDigest(String value) {
        return new QualityEligibilitySnapshotEvidence(
                snapshotId, batchId, sourceId, result, observationWindow, cutoffAt,
                watermark, value, sourceSchemaVersion, sourceSchemaDigest, qmdpVersion,
                qmdpDigest, qualityGateVersion, qualityGateDigest, qshmVersion, qshmDigest,
                lineageId, effectiveAt, immutableHash, formulaEvidence);
    }

    private static UUID uuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static String digest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
        return value;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_SNAPSHOT_EVIDENCE_INVALID");
    }
}
