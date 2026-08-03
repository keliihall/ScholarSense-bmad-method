package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;
import java.util.Optional;

/** Transport-neutral authorization input. It carries no display claim or raw object identifier. */
public record CompositeAuthorizationRequest(
        String actorPseudonym,
        String objectClass,
        String actionId,
        String objectTokenDigest,
        long expectedObjectVersion,
        Optional<String> studentSourceRefDigest,
        Optional<String> accessLineageId,
        String traceId) {
    public CompositeAuthorizationRequest {
        if (actorPseudonym == null || actorPseudonym.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ACTOR_REQUIRED");
        }
        if (objectClass == null || !objectClass.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_CLASS_INVALID");
        }
        if (actionId == null
                || !actionId.matches("[a-z][a-z0-9.-]{2,127}")
                || actionId.contains("/")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ACTION_NOT_EXPANDED");
        }
        if (objectTokenDigest == null || !objectTokenDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_TOKEN_INVALID");
        }
        if (expectedObjectVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_OBJECT_VERSION_INVALID");
        }
        studentSourceRefDigest = Objects.requireNonNull(
                studentSourceRefDigest, "studentSourceRefDigest");
        studentSourceRefDigest.ifPresent(value -> {
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_STUDENT_REF_INVALID");
            }
        });
        accessLineageId = Objects.requireNonNull(accessLineageId, "accessLineageId");
        accessLineageId.ifPresent(value -> {
            if (!value.matches("lin_[A-Za-z0-9_-]{32,128}")) {
                throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_LINEAGE_INVALID");
            }
        });
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_TRACE_INVALID");
        }
    }
}
