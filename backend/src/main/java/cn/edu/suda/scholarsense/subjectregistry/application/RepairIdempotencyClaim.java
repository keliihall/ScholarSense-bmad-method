package cn.edu.suda.scholarsense.subjectregistry.application;

import java.util.Optional;

public record RepairIdempotencyClaim(Status status, Optional<RepairIdempotencyResult> result) {
    public enum Status { FRESH, REPLAY, MISMATCH }

    public RepairIdempotencyClaim {
        result = result == null ? Optional.empty() : result;
        if ((status == Status.REPLAY) != result.isPresent()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_IDEMPOTENCY_CLAIM_INVALID");
        }
    }

    public static RepairIdempotencyClaim fresh() {
        return new RepairIdempotencyClaim(Status.FRESH, Optional.empty());
    }

    public static RepairIdempotencyClaim replay(RepairIdempotencyResult result) {
        return new RepairIdempotencyClaim(Status.REPLAY, Optional.of(result));
    }

    public static RepairIdempotencyClaim mismatch() {
        return new RepairIdempotencyClaim(Status.MISMATCH, Optional.empty());
    }
}
