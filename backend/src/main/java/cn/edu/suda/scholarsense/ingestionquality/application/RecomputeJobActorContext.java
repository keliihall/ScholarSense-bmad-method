package cn.edu.suda.scholarsense.ingestionquality.application;

public record RecomputeJobActorContext(String actorPseudonym, String sourceIp) {
    public RecomputeJobActorContext {
        if (actorPseudonym == null || actorPseudonym.isBlank() || actorPseudonym.length() > 128
                || sourceIp == null || sourceIp.isBlank() || sourceIp.length() > 64) {
            throw new IllegalArgumentException("INGESTION_QUALITY_ACTOR_CONTEXT_INVALID");
        }
    }
}
