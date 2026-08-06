package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuthorizationDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** RFP-1.0.0 adapter: every owned source/dependency is checked at request time. */
public final class CompositeCatalogAuthorizationAdapter implements CatalogAuthorizationPort {
    private final CompositeAuthorizationPort authorization;

    public CompositeCatalogAuthorizationAdapter(CompositeAuthorizationPort authorization) {
        this.authorization = Objects.requireNonNull(authorization);
    }

    @Override
    public CatalogAuthorizationDecision authorize(
            String actorRef, String sourceAction, String dependencyAction,
            DataSourceCatalog catalog, String traceId) {
        CatalogAuthorizationDecision result = CatalogAuthorizationDecision.ALLOW;
        for (var source : catalog.sources()) {
            result = combine(result, decision(
                    actorRef, "SOURCE", sourceAction, source.sourceId(),
                    catalog.aggregateVersion(), traceId));
        }
        for (var dependency : catalog.dependencies()) {
            result = combine(result, decision(
                    actorRef, "DEPENDENCY", dependencyAction, dependency.dependencyId(),
                    catalog.aggregateVersion(), traceId));
        }
        return result;
    }

    private CatalogAuthorizationDecision decision(
            String actorRef, String objectClass, String action, String objectId,
            long version, String traceId) {
        var request = new CompositeAuthorizationRequest(
                actorRef, objectClass, action, digest(objectId), version,
                Optional.empty(), Optional.empty(), traceId);
        CompositeAuthorizationOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    Objects.requireNonNull(
                            authorization.authorize(request), "authorization decision")
                            .outcome(),
                    "authorization outcome");
        } catch (RuntimeException unavailable) {
            return CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE;
        }
        return switch (outcome) {
            case ALLOW -> CatalogAuthorizationDecision.ALLOW;
            case DENY -> CatalogAuthorizationDecision.DENY;
            case DEPENDENCY_UNAVAILABLE -> CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE;
        };
    }

    private static CatalogAuthorizationDecision combine(
            CatalogAuthorizationDecision left, CatalogAuthorizationDecision right) {
        if (left == CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE
                || right == CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE) {
            return CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE;
        }
        if (left == CatalogAuthorizationDecision.DENY || right == CatalogAuthorizationDecision.DENY) {
            return CatalogAuthorizationDecision.DENY;
        }
        return CatalogAuthorizationDecision.ALLOW;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
