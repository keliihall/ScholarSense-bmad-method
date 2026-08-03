package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;

public record CompositeAuthorizationRecheckRequest(
        CompositeAuthorizationRequest currentRequest,
        CompositeAuthorizationDecisionToken priorDecisionToken) {
    public CompositeAuthorizationRecheckRequest {
        Objects.requireNonNull(currentRequest, "currentRequest");
        Objects.requireNonNull(priorDecisionToken, "priorDecisionToken");
    }
}
