package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.List;

@FunctionalInterface
public interface AuditSearchTokenQueryPort {
    List<AuditSearchQueryToken> query(AuditSearchTokenQuery query);

    static AuditSearchTokenQueryPort productionFailClosed() {
        return query -> {
            if (query == null) {
                throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_QUERY_REQUIRED");
            }
            throw new IllegalStateException("AUDIT_SEARCH_TOKENIZATION_BINDING_UNAVAILABLE");
        };
    }
}
