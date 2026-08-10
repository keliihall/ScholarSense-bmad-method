package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DataBatchCommandFingerprint {
    private DataBatchCommandFingerprint() {}

    static String digest(ReceiveDataBatchCommand command) {
        BatchLineage lineage = command.lineage();
        return hash(
                DataBatchCommandType.RECEIVE.action(), command.batchId(),
                command.expectedAggregateVersion(), command.identity().sourceId(),
                command.identity().businessKey(), command.identity().sourceVersion(),
                command.declaredManifestDigest(), lineage.lineageId(),
                lineage.supersedesBatchId(), lineage.reasonCode(), lineage.effectiveAt());
    }

    static String digest(SealDataBatchCommand command) {
        BatchManifest manifest = command.manifest();
        return hash(
                DataBatchCommandType.SEAL.action(), command.batchId(),
                command.expectedAggregateVersion(), manifest.recordCount(),
                manifest.validRecordCount(), manifest.rejectedRecordCount(),
                manifest.observationWindow().startAt(), manifest.observationWindow().endAt(),
                manifest.cutoffAt(), manifest.timezone(), manifest.watermark(),
                manifest.sourceSchemaVersion(), manifest.sourceSchemaDigest(),
                manifest.dataCatalogVersion(), manifest.dataCatalogDigest(),
                manifest.qualityGateVersion(), manifest.qualityGateDigest(),
                manifest.qualityMetricDecisionProfileVersion(),
                manifest.qualityMetricDecisionProfileDigest(), manifest.sourceOccurredAt(),
                manifest.scheduledDueAt(), manifest.receivedAt(), manifest.laneId(),
                manifest.manifestDigest());
    }

    static String digest(EvaluateDataBatchCommand command) {
        return hash(
                DataBatchCommandType.EVALUATE.action(), command.batchId(),
                command.expectedAggregateVersion());
    }

    static String digest(PublishDataBatchCommand command) {
        return hash(
                DataBatchCommandType.PUBLISH.action(), command.batchId(),
                command.expectedAggregateVersion());
    }

    static String scopeDigest(DataBatchIdempotencyScope scope) {
        return hashHex(
                scope.tenantId(), scope.actorRef(), scope.commandType().name(),
                scope.idempotencyKey());
    }

    private static String hash(Object... values) {
        return "sha256:" + hashHex(values);
    }

    private static String hashHex(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = (value == null ? "<null>" : value.toString())
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
