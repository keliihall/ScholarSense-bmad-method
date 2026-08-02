package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAction;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAuthorizationContext;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRequest;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.util.List;
import java.util.Map;

/** Fail-closed LocalAuditFact successor binding for invalidation facts. */
public final class AccessInvalidationAuditAdapter
        implements AccessInvalidationAuditPort {
    private final IdentityAuditFactFactory facts;
    private final IdentityAuditPort audit;

    public AccessInvalidationAuditAdapter(
            IdentityAuditFactFactory facts,
            IdentityAuditPort audit) {
        this.facts = facts;
        this.audit = audit;
    }

    @Override
    public void factAppended(AccessInvalidationFact fact) {
        audit.append(facts.create(new IdentityAuditRequest(
                ActorType.SERVICE,
                "access-invalidation-worker",
                List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable",
                        null,
                        List.of(),
                        List.of(),
                        "SERVICE_OPERATION"),
                IdentityAuditAction.ACCESS_INVALIDATION_PUBLISHED,
                "accepted",
                "ACCESS_INVALIDATION_"
                        + fact.changeKind().name(),
                "access-invalidation-fact",
                fact.eventId().toString(),
                "ACCESS_INVALIDATION_PROPAGATION",
                "ACCESS_INVALIDATION",
                null,
                fact.traceId(),
                fact.effectiveAt(),
                "access-invalidation-fact",
                fact.lineageId().value(),
                fact.aggregateVersion(),
                fact.eventId().toString(),
                Map.of(
                        "accessInvalidationContract",
                        "ACCESS-INVALIDATION-DATA-1.0.0",
                        "retentionSchedule",
                        "RS-1.0.0",
                        "roleFieldPolicy",
                        "RFP-1.0.0"))));
    }
}
