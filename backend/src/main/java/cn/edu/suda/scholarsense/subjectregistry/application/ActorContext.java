package cn.edu.suda.scholarsense.subjectregistry.application;

public record ActorContext(
        String tenantId,
        String actorPseudonym,
        String auditActorRef,
        String sourceIp) {
    public ActorContext {
        require(tenantId, 128);
        require(actorPseudonym, 128);
        require(auditActorRef, 128);
        require(sourceIp, 64);
    }

    private static void require(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_ACTOR_CONTEXT_INVALID");
        }
    }
}
