package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAction;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAuthorizationContext;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRequest;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncAuditEvent;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncAuditPort;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.util.List;
import java.util.Map;

/** Converts sync events to the identity-owned LocalAuditFact + outbox contract. */
public final class IdentitySyncAuditAdapter implements IdentitySyncAuditPort {
    private final IdentityAuditFactFactory facts;
    private final IdentityAuditPort audit;

    public IdentitySyncAuditAdapter(
            IdentityAuditFactFactory facts, IdentityAuditPort audit) {
        this.facts = facts;
        this.audit = audit;
    }

    @Override
    public void append(IdentitySyncAuditEvent event) {
        IdentityAuditAction action = switch (event.action()) {
            case "identity.sync.applied" -> IdentityAuditAction.SYNC_APPLIED;
            case "identity.sync.rejected" -> IdentityAuditAction.SYNC_REJECTED;
            case "identity.sync.failed" -> IdentityAuditAction.SYNC_FAILED;
            case "identity.sync.reconciled" -> IdentityAuditAction.SYNC_RECONCILED;
            case "responsibility.sync.applied",
                    "responsibility.sync.heartbeat" ->
                    IdentityAuditAction.RESPONSIBILITY_SYNC_APPLIED;
            case "responsibility.sync.rejected",
                    "responsibility.sync.failed" ->
                    IdentityAuditAction.RESPONSIBILITY_SYNC_REJECTED;
            case "responsibility.sync.reconciled" ->
                    IdentityAuditAction.RESPONSIBILITY_SYNC_RECONCILED;
            case "responsibility.exception.opened" ->
                    IdentityAuditAction.RESPONSIBILITY_EXCEPTION_OPENED;
            case "responsibility.exception.resolved" ->
                    IdentityAuditAction.RESPONSIBILITY_EXCEPTION_RESOLVED;
            default -> throw new IllegalArgumentException(
                    "IDENTITY_SYNC_AUDIT_ACTION_INVALID");
        };
        boolean responsibility = action.name().startsWith("RESPONSIBILITY_");
        boolean reconciliation =
                action == IdentityAuditAction.SYNC_RECONCILED
                        || action
                                == IdentityAuditAction
                                        .RESPONSIBILITY_SYNC_RECONCILED;
        boolean exception =
                action
                                == IdentityAuditAction
                                        .RESPONSIBILITY_EXCEPTION_OPENED
                        || action
                                == IdentityAuditAction
                                        .RESPONSIBILITY_EXCEPTION_RESOLVED;
        String objectType = exception
                ? "responsibility-exception"
                : reconciliation
                        ? responsibility
                                ? "responsibility-reconciliation"
                                : "identity-reconciliation"
                        : responsibility
                                ? "responsibility-sync-job"
                                : "identity-sync-job";
        String purpose = exception
                ? "RESPONSIBILITY_EXCEPTION_MANAGEMENT"
                : reconciliation
                        ? responsibility
                                ? "RESPONSIBILITY_AUTHORITY_RECONCILIATION"
                                : "IDENTITY_AUTHORITY_RECONCILIATION"
                        : responsibility
                                ? "RESPONSIBILITY_AUTHORITY_SYNC"
                                : "IDENTITY_AUTHORITY_SYNC";
        Map<String, String> policies = Map.copyOf(event.policyVersions());
        String outcome = action == IdentityAuditAction.SYNC_FAILED
                || action
                        == IdentityAuditAction
                                .RESPONSIBILITY_SYNC_REJECTED
                ? "rejected" : event.outcome();
        String evidenceBinding = event.jobId()
                + ":" + event.attemptNo()
                + ":" + event.fencingToken()
                + ":" + event.sourceVersion()
                + ":" + event.sourceWatermark()
                + ":" + event.occurredAt();
        audit.append(facts.create(new IdentityAuditRequest(
                ActorType.SERVICE,
                "identity-sync-worker",
                List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable",
                        null,
                        List.of(),
                        List.of(),
                        "SERVICE_OPERATION"),
                action,
                outcome,
                event.reasonCode(),
                objectType,
                event.auditedObjectId().toString(),
                purpose,
                responsibility ? "RESPONSIBILITY" : "IDENTITY_ORG",
                null,
                event.traceId(),
                event.occurredAt(),
                objectType,
                evidenceBinding,
                event.aggregateVersion() > 0 ? event.aggregateVersion() : null,
                evidenceBinding
                        + ":"
                        + event.action()
                        + ":"
                        + event.auditedObjectId(),
                policies)));
    }
}
