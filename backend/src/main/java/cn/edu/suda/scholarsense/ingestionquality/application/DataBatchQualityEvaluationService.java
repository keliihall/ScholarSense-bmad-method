package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityAssessment;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricCalculator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotCanonicalizer;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Prepares a complete policy-bound snapshot and revalidates its contract before any owner write. */
public final class DataBatchQualityEvaluationService {
    private static final String CONTRACT_INVALID = "INGESTION_QUALITY_CONTRACT_INVALID";
    private static final String EVIDENCE_INVALID = "INGESTION_QUALITY_EVIDENCE_INVALID";
    private static final String PREDECESSOR_INVALID =
            "INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID";
    private static final String PLACEHOLDER_HASH = "sha256:" + "1".repeat(64);

    private final ExecutableQualityPolicyGuard guard;
    private final QualityMeasurementPort measurement;
    private final DataBatchReadPort batches;
    private final QualitySnapshotReadPort snapshots;
    private final QualitySnapshotIdPort snapshotIds;
    private final QualityMetricCalculator calculator = new QualityMetricCalculator();

    public DataBatchQualityEvaluationService(
            ExecutableQualityPolicyGuard guard,
            QualityMeasurementPort measurement,
            DataBatchReadPort batches,
            QualitySnapshotReadPort snapshots,
            QualitySnapshotIdPort snapshotIds) {
        this.guard = Objects.requireNonNull(guard);
        this.measurement = Objects.requireNonNull(measurement);
        this.batches = Objects.requireNonNull(batches);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.snapshotIds = Objects.requireNonNull(snapshotIds);
    }

    boolean uses(
            ExecutableQualityPolicyGuard candidateGuard,
            DataBatchReadPort candidateBatches,
            QualitySnapshotReadPort candidateSnapshots) {
        return guard == candidateGuard
                && batches == candidateBatches
                && snapshots == candidateSnapshots;
    }

    SealedQualityContractEvidence prepareSeal(
            DataBatch receivingBatch,
            BatchManifest manifest,
            Instant sealedAt) {
        Objects.requireNonNull(receivingBatch);
        VerifiedQualityContract captured = guard.capture();
        validateManifest(receivingBatch, manifest, captured, sealedAt);
        return evidence(captured.attestation());
    }

    void revalidate(SealedQualityContractEvidence evidence) {
        guard.revalidate(attestation(evidence));
    }

    PreparedDataBatchAssessment evaluate(
            DataBatch sealedBatch,
            Instant evaluatedAt,
            String traceId) {
        if (sealedBatch == null || sealedBatch.status() != DataBatchStatus.SEALED) {
            throw contractInvalid();
        }
        VerifiedQualityContract captured = guard.capture();
        requireCapturedContract(sealedBatch, captured);
        validateManifest(sealedBatch, sealedBatch.manifest(), captured, evaluatedAt);

        List<MetricDefinition> definitions = QualityMetricCalculator.orderedDefinitions(
                captured.policy(), sealedBatch.identity().sourceId());
        QualityMeasurement measured = Objects.requireNonNull(
                measurement.measure(sealedBatch, definitions));
        if (!QualityMeasurementAnchor.from(sealedBatch).equals(measured.anchor())) {
            throw evidenceInvalid();
        }
        QualityAssessment assessment = calculator.assess(
                captured.policy(), sealedBatch.identity().sourceId(),
                measured.inputsByFormula());
        QualitySnapshotCanonicalizer canonicalizer = new QualitySnapshotCanonicalizer(
                captured.policy(), captured.hashProfile());
        UUID predecessorSnapshotId = directPredecessor(sealedBatch, canonicalizer);
        DataBatch updated = sealedBatch.recordQualityResult(
                assessment.overallResult() == QualityOverallResult.QUALITY_PASSED,
                evaluatedAt);
        UUID snapshotId = Objects.requireNonNull(snapshotIds.nextId(evaluatedAt));
        QualitySnapshot provisional = snapshot(
                updated, assessment, measured.impactScopeCodes(), predecessorSnapshotId,
                snapshotId, evaluatedAt, traceId, captured, PLACEHOLDER_HASH);
        String immutableHash = canonicalizer.immutableHash(provisional);
        QualitySnapshot completed = snapshot(
                updated, assessment, measured.impactScopeCodes(), predecessorSnapshotId,
                snapshotId, evaluatedAt, traceId, captured, immutableHash);
        VerifiedQualitySnapshot verified = VerifiedQualitySnapshot.verify(completed, canonicalizer);

        return new PreparedDataBatchAssessment(
                updated, verified, evidence(captured.attestation()));
    }

    QualitySnapshot validatePublish(DataBatch passedBatch) {
        if (passedBatch == null || passedBatch.status() != DataBatchStatus.QUALITY_PASSED) {
            throw contractInvalid();
        }
        VerifiedQualityContract captured = guard.capture();
        requireCapturedContract(passedBatch, captured);
        validateManifest(passedBatch, passedBatch.manifest(), captured, passedBatch.evaluatedAt());
        QualitySnapshot snapshot = snapshots.findByBatchId(passedBatch.batchId())
                .orElseThrow(DataBatchQualityEvaluationService::predecessorInvalid);
        QualitySnapshotCanonicalizer canonicalizer = new QualitySnapshotCanonicalizer(
                captured.policy(), captured.hashProfile());
        UUID expectedPredecessor = directPredecessor(passedBatch, canonicalizer);
        if (!matchesAssessedBatch(snapshot, passedBatch, expectedPredecessor)) {
            throw contractInvalid();
        }
        try {
            canonicalizer.verify(snapshot);
        } catch (RuntimeException invalidSnapshot) {
            throw contractInvalid();
        }
        guard.revalidate(captured.attestation());
        return snapshot;
    }

    private UUID directPredecessor(
            DataBatch batch,
            QualitySnapshotCanonicalizer canonicalizer) {
        UUID predecessorBatchId = batch.lineage().supersedesBatchId();
        if (predecessorBatchId == null) return null;
        DataBatch predecessorBatch = batches.find(predecessorBatchId)
                .orElseThrow(DataBatchQualityEvaluationService::predecessorInvalid);
        QualitySnapshot predecessor = snapshots.findByBatchId(predecessorBatchId)
                .orElseThrow(DataBatchQualityEvaluationService::predecessorInvalid);
        UUID storedPredecessor = predecessor.supersedesSnapshotId();
        boolean predecessorShapeMatches = predecessorBatch.lineage().supersedesBatchId() == null
                ? storedPredecessor == null
                : storedPredecessor != null;
        if (!predecessorBatch.batchId().equals(predecessorBatchId)
                || !predecessor.batchId().equals(predecessorBatchId)
                || !predecessorBatch.identity().sourceId().equals(batch.identity().sourceId())
                || !predecessorBatch.lineage().lineageId().equals(batch.lineage().lineageId())
                || !predecessorShapeMatches
                || !matchesAssessedBatch(
                        predecessor, predecessorBatch, storedPredecessor)) {
            throw predecessorInvalid();
        }
        try {
            canonicalizer.verify(predecessor);
        } catch (RuntimeException invalidPredecessor) {
            throw predecessorInvalid();
        }
        return predecessor.snapshotId();
    }

    private static boolean matchesAssessedBatch(
            QualitySnapshot snapshot,
            DataBatch batch,
            UUID expectedPredecessor) {
        BatchManifest manifest = batch.manifest();
        DataBatchStatus expectedStatus;
        QualityOverallResult expectedOverall;
        long expectedAggregateVersion;
        if (batch.status() == DataBatchStatus.QUALITY_PASSED
                || batch.status() == DataBatchStatus.PUBLISHED) {
            expectedStatus = DataBatchStatus.QUALITY_PASSED;
            expectedOverall = QualityOverallResult.QUALITY_PASSED;
            expectedAggregateVersion = batch.status() == DataBatchStatus.PUBLISHED
                    ? batch.aggregateVersion() - 1 : batch.aggregateVersion();
        } else if (batch.status() == DataBatchStatus.QUALITY_FAILED) {
            expectedStatus = DataBatchStatus.QUALITY_FAILED;
            expectedOverall = QualityOverallResult.QUALITY_FAILED;
            expectedAggregateVersion = batch.aggregateVersion();
        } else {
            return false;
        }
        return snapshot.batchId().equals(batch.batchId())
                && snapshot.sourceId().equals(batch.identity().sourceId())
                && snapshot.lineageId().equals(batch.lineage().lineageId())
                && Objects.equals(snapshot.supersedesSnapshotId(), expectedPredecessor)
                && snapshot.assessedBatchStatus() == expectedStatus
                && snapshot.overallResult() == expectedOverall
                && snapshot.observationWindow().equals(manifest.observationWindow())
                && snapshot.cutoffAt().equals(manifest.cutoffAt())
                && snapshot.watermark().equals(manifest.watermark())
                && snapshot.manifestDigest().equals(manifest.manifestDigest())
                && snapshot.sourceSchemaVersion().equals(manifest.sourceSchemaVersion())
                && snapshot.sourceSchemaDigest().equals(manifest.sourceSchemaDigest())
                && snapshot.qualityMetricDecisionProfileVersion().equals(
                        manifest.qualityMetricDecisionProfileVersion())
                && snapshot.qualityMetricDecisionProfileDigest().equals(
                        manifest.qualityMetricDecisionProfileDigest())
                && snapshot.qualityGateVersion().equals(manifest.qualityGateVersion())
                && snapshot.qualityGateDigest().equals(manifest.qualityGateDigest())
                && snapshot.evaluatedAt().equals(batch.evaluatedAt())
                && snapshot.aggregateVersion() == expectedAggregateVersion;
    }

    private static QualitySnapshot snapshot(
            DataBatch updated,
            QualityAssessment assessment,
            List<String> impactScopes,
            UUID predecessorSnapshotId,
            UUID snapshotId,
            Instant evaluatedAt,
            String traceId,
            VerifiedQualityContract contract,
            String immutableHash) {
        BatchManifest manifest = updated.manifest();
        SourcePolicy source = uniqueSource(contract.policy(), updated.identity().sourceId());
        return new QualitySnapshot(
                contract.hashProfile().domainTag(), contract.hashProfile().hashProfileVersion(),
                contract.attestation().qshmProfileCanonicalDigest(), updated.batchId(),
                updated.identity().sourceId(), updated.status(), assessment.overallResult(),
                manifest.observationWindow(), manifest.cutoffAt(), manifest.watermark(),
                assessment.metricResults(), impactScopes, source.owner(),
                contract.policy().approvalRef(), contract.policy().effectiveAt(), "RS-1.0.0",
                contract.policy().profileVersion(),
                contract.attestation().qmdpPolicyCanonicalDigest(),
                manifest.qualityGateVersion(), manifest.qualityGateDigest(),
                contract.policy().canonicalization().profile(), manifest.manifestDigest(),
                manifest.sourceSchemaVersion(), manifest.sourceSchemaDigest(),
                updated.lineage().lineageId(), predecessorSnapshotId, snapshotId, evaluatedAt,
                traceId, updated.aggregateVersion(), immutableHash);
    }

    private static void validateManifest(
            DataBatch batch,
            BatchManifest manifest,
            VerifiedQualityContract contract,
            Instant actionAt) {
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(actionAt);
        ExecutableQualityPolicy policy = contract.policy();
        SourcePolicy source = uniqueSource(policy, batch.identity().sourceId());
        var dataCatalog = policy.controlledInputs().dataCatalog();
        var qualityGate = policy.controlledInputs().qualityGate();
        Duration observationDuration = Duration.between(
                manifest.observationWindow().startAt(),
                manifest.observationWindow().endAt());
        Duration requiredDuration = Duration.ofHours(
                policy.windowSemantics().freshnessWindowHours());
        boolean laneKnown = source.freshnessLanes().stream()
                .anyMatch(lane -> lane.laneId().equals(manifest.laneId()));
        if (!manifest.sourceSchemaVersion().equals(source.schemaBinding().version())
                || !manifest.sourceSchemaDigest().equals(source.schemaBinding().canonicalDigest())
                || !manifest.dataCatalogVersion().equals(dataCatalog.version())
                || !manifest.dataCatalogDigest().equals(dataCatalog.canonicalDigest())
                || !manifest.qualityGateVersion().equals(qualityGate.version())
                || !manifest.qualityGateDigest().equals(qualityGate.canonicalDigest())
                || !manifest.qualityMetricDecisionProfileVersion().equals(policy.profileVersion())
                || !manifest.qualityMetricDecisionProfileDigest().equals(
                        contract.attestation().qmdpPolicyCanonicalDigest())
                || !manifest.observationWindow().endAt().equals(manifest.cutoffAt())
                || !observationDuration.equals(requiredDuration)
                || !laneKnown
                || contract.attestation().qmdpEffectiveAt().isAfter(actionAt)
                || contract.attestation().qshmEffectiveAt().isAfter(actionAt)) {
            throw contractInvalid();
        }
        requireMicrosecond(manifest.observationWindow().startAt());
        requireMicrosecond(manifest.observationWindow().endAt());
        requireMicrosecond(manifest.cutoffAt());
        requireMicrosecond(manifest.sourceOccurredAt());
        requireMicrosecond(manifest.scheduledDueAt());
        requireMicrosecond(manifest.receivedAt());
    }

    private static void requireCapturedContract(
            DataBatch batch,
            VerifiedQualityContract captured) {
        SealedQualityContractEvidence stored = batch.sealedQualityContractEvidence();
        if (stored == null || !stored.equals(evidence(captured.attestation()))) {
            throw contractInvalid();
        }
    }

    private static SourcePolicy uniqueSource(
            ExecutableQualityPolicy policy,
            String sourceId) {
        List<SourcePolicy> matches = policy.sources().stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .toList();
        if (matches.size() != 1) throw contractInvalid();
        return matches.getFirst();
    }

    private static SealedQualityContractEvidence evidence(QualityContractAttestation value) {
        return new SealedQualityContractEvidence(
                value.qmdpProfileVersion(), value.qmdpPolicyRawDigest(),
                value.qmdpPolicyCanonicalDigest(), value.qmdpContractLockVersion(),
                value.qmdpContractLockRawDigest(), value.qmdpContractLockCanonicalDigest(),
                value.qmdpAuthorityRef(), value.qmdpApprovalRef(), value.qmdpEffectiveAt(),
                value.qshmProfileVersion(), value.qshmProfileRawDigest(),
                value.qshmProfileCanonicalDigest(), value.qshmContractLockVersion(),
                value.qshmContractLockRawDigest(), value.qshmAuthorityRef(),
                value.qshmApprovalRef(), value.qshmEffectiveAt());
    }

    private static QualityContractAttestation attestation(SealedQualityContractEvidence value) {
        Objects.requireNonNull(value);
        return new QualityContractAttestation(
                value.qmdpProfileVersion(), value.qmdpPolicyRawDigest(),
                value.qmdpPolicyCanonicalDigest(), value.qmdpContractLockVersion(),
                value.qmdpContractLockRawDigest(), value.qmdpContractLockCanonicalDigest(),
                value.qmdpAuthorityRef(), value.qmdpApprovalRef(), value.qmdpEffectiveAt(),
                value.qshmProfileVersion(), value.qshmProfileRawDigest(),
                value.qshmProfileCanonicalDigest(), value.qshmContractLockVersion(),
                value.qshmContractLockRawDigest(), value.qshmAuthorityRef(),
                value.qshmApprovalRef(), value.qshmEffectiveAt());
    }

    private static void requireMicrosecond(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw contractInvalid();
    }

    private static IngestionQualityApplicationException contractInvalid() {
        return new IngestionQualityApplicationException(CONTRACT_INVALID);
    }

    private static IngestionQualityApplicationException evidenceInvalid() {
        return new IngestionQualityApplicationException(EVIDENCE_INVALID);
    }

    private static IngestionQualityApplicationException predecessorInvalid() {
        return new IngestionQualityApplicationException(PREDECESSOR_INVALID);
    }
}
