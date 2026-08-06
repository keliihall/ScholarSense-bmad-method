package cn.edu.suda.scholarsense.ingestionquality.application;

/** Keeps the active session authorization key separate from the stable audit actor identity. */
public record CatalogActorContext(
        String authorizationSessionRef,
        String auditActorRef,
        String sourceIp) {
    public CatalogActorContext {
        require(authorizationSessionRef, "INGESTION_QUALITY_SESSION_REF_REQUIRED");
        require(auditActorRef, "INGESTION_QUALITY_AUDIT_ACTOR_REQUIRED");
        require(sourceIp, "INGESTION_QUALITY_SOURCE_IP_REQUIRED");
    }

    private static void require(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    }
}
