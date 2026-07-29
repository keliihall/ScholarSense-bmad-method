package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IdentityAuditRequest(
        ActorType actorType,
        String actorIdentity,
        List<String> roleIds,
        IdentityAuditAuthorizationContext authorizationContext,
        IdentityAuditAction action,
        String outcome,
        String reasonCode,
        String objectType,
        String objectIdentity,
        String purpose,
        String projectionScope,
        String sourceIp,
        String traceId,
        Instant sourceOccurredAt,
        String aggregateType,
        String aggregateIdentity,
        Long aggregateVersion,
        String idempotencyKey,
        Map<String, String> policyVersions) {
    public IdentityAuditRequest {
        roleIds = List.copyOf(roleIds);
        policyVersions = Map.copyOf(policyVersions);
    }

    public IdentityAuditRequest(
            ActorType actorType,
            String actorIdentity,
            List<String> roleIds,
            IdentityAuditAuthorizationContext authorizationContext,
            IdentityAuditAction action,
            String outcome,
            String reasonCode,
            String objectType,
            String objectIdentity,
            String purpose,
            String projectionScope,
            String sourceIp,
            String traceId,
            String aggregateType,
            String aggregateIdentity,
            Long aggregateVersion,
            String idempotencyKey,
            Map<String, String> policyVersions) {
        this(
                actorType, actorIdentity, roleIds, authorizationContext, action,
                outcome, reasonCode, objectType, objectIdentity, purpose,
                projectionScope, sourceIp, traceId, null, aggregateType,
                aggregateIdentity, aggregateVersion, idempotencyKey, policyVersions);
    }
}
