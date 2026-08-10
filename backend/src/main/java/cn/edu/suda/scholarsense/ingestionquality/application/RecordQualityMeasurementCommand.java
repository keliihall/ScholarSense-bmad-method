package cn.edu.suda.scholarsense.ingestionquality.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RecordQualityMeasurementCommand(
        UUID batchId,
        String formulaId,
        int formulaOrdinal,
        boolean applicable,
        Map<String, BigInteger> operands,
        String evidenceDigest,
        Instant recordedAt) {
    public RecordQualityMeasurementCommand {
        batchId = DataBatchCommandRules.uuidV7(batchId);
        formulaId = DataBatchCommandRules.text(formulaId, 256);
        if (formulaOrdinal < 0 || formulaOrdinal > 13) throw invalid();
        operands = Map.copyOf(Objects.requireNonNull(operands));
        if (operands.size() > 2) throw invalid();
        for (var operand : operands.entrySet()) {
            DataBatchCommandRules.text(operand.getKey(), 128);
            BigInteger value = Objects.requireNonNull(operand.getValue());
            if (value.signum() < 0
                    || value.compareTo(BigInteger.valueOf(9_007_199_254_740_991L)) > 0) {
                throw invalid();
            }
        }
        evidenceDigest = DataBatchCommandRules.digest(evidenceDigest);
        Objects.requireNonNull(recordedAt);
        if (recordedAt.getNano() % 1_000 != 0) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
    }
}
