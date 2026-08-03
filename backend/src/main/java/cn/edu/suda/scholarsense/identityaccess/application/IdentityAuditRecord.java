package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.util.Objects;
import java.util.Optional;

public record IdentityAuditRecord(
        LocalAuditFact fact,
        LocalAuditOutboxRecord outbox,
        Optional<AuthorizationDecisionAuditContext> authorizationDecisionContext) {
    public IdentityAuditRecord(LocalAuditFact fact, LocalAuditOutboxRecord outbox) {
        this(fact, outbox, Optional.empty());
    }

    public IdentityAuditRecord {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(authorizationDecisionContext, "authorizationDecisionContext");
    }
}
