package cn.edu.suda.scholarsense.subjectregistry.application;

public record RepairIdempotencyScope(
        String tenantId,
        String actorPseudonym,
        String commandType,
        String idempotencyKey) {
    public RepairIdempotencyScope {
        if (tenantId == null || actorPseudonym == null
                || !"subject-mapping.repair".equals(commandType)
                || idempotencyKey == null) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_IDEMPOTENCY_SCOPE_INVALID");
        }
    }
}
