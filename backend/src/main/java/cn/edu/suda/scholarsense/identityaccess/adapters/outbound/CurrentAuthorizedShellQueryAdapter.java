package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContextQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentAuthorizedShellProjection;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentAuthorizedShellQueryPort;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentAuthorizedShellService;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditKind;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditRequest;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.InstalledShellCapability;
import cn.edu.suda.scholarsense.identityaccess.application.ShellCapabilityState;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveReadTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rebuilds the shell from current session and authority facts on every request. */
public final class CurrentAuthorizedShellQueryAdapter
        implements CurrentAuthorizedShellQueryPort {
    private final IdentitySessionRepository sessions;
    private final AuthoritativeIdentityContextQueryPort identities;
    private final List<AuthorizedShellCapabilityProvider> providers;
    private final TrustedTimeSource time;
    private final CurrentAuthorizedShellService shells;
    private final AuthorizationAuditPort audit;
    private final SensitiveReadTransactionPort transactions;

    public CurrentAuthorizedShellQueryAdapter(
            IdentitySessionRepository sessions,
            AuthoritativeIdentityContextQueryPort identities,
            List<AuthorizedShellCapabilityProvider> providers,
            TrustedTimeSource time,
            RoleFieldPolicyCatalog policy,
            AuthorizationAuditPort audit,
            SensitiveReadTransactionPort transactions) {
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.providers = List.copyOf(providers);
        this.time = java.util.Objects.requireNonNull(time);
        this.shells = new CurrentAuthorizedShellService(policy);
        this.audit = java.util.Objects.requireNonNull(audit);
        this.transactions = java.util.Objects.requireNonNull(transactions);
    }

    @Override
    public CurrentAuthorizedShellProjection current(
            String internalSessionId, String traceId, String sourceIp) {
        return transactions.execute(
                () -> currentInTransaction(internalSessionId, traceId, sourceIp));
    }

    private CurrentAuthorizedShellProjection currentInTransaction(
            String internalSessionId, String traceId, String sourceIp) {
        var session = sessions.findById(internalSessionId).orElseThrow(() ->
                new IdentityAccessException(
                        "IDENTITY_SESSION_REQUIRED", "authentication is required"));
        Instant now;
        try {
            now = time.now().instant();
        } catch (RuntimeException unavailable) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), Set.of(),
                    1, "DEPENDENCY_UNAVAILABLE", Instant.EPOCH, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        if (!session.activeAt(now)) {
            throw new IdentityAccessException(
                    "IDENTITY_SESSION_EXPIRED", "authentication is required");
        }
        cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext identity;
        try {
            identity = identities.findCurrent(session.actorPseudonym()).orElse(null);
        } catch (RuntimeException unavailable) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), Set.of(),
                    1, "DEPENDENCY_UNAVAILABLE", now, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        if (identity == null) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), Set.of(),
                    1, "DEPENDENCY_UNAVAILABLE", now, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        Set<RolePackage> roles;
        try {
            roles = identity.roleIds().stream()
                    .map(RolePackage::fromAuthorityId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException unavailable) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), Set.of(),
                    identity.aggregateVersion(), "DEPENDENCY_UNAVAILABLE",
                    now, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        if (identity.freshness() != IdentityFreshness.FRESH
                || !RoleFieldPolicyCatalog.POLICY_VERSION.equals(
                        identity.policyVersions().get("roleFieldPolicy"))) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), roles,
                    identity.aggregateVersion(), "DEPENDENCY_UNAVAILABLE",
                    now, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        List<InstalledShellCapability> installed = new ArrayList<>();
        try {
            for (AuthorizedShellCapabilityProvider provider : providers) {
                provider.capabilities().forEach(capability -> installed.add(
                        new InstalledShellCapability(
                                capability.id(),
                                capability.label(),
                                capability.routeName(),
                                ShellCapabilityState.valueOf(capability.state().name()),
                                capability.authorizedRoleIds().stream()
                                        .map(RolePackage::fromAuthorityId)
                                        .collect(Collectors.toUnmodifiableSet()))));
            }
        } catch (RuntimeException unavailable) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), roles,
                    identity.aggregateVersion(), "DEPENDENCY_UNAVAILABLE",
                    now, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        CurrentAuthorizedShellProjection projection;
        try {
            projection = shells.project(roles, installed, now);
        } catch (RuntimeException unavailable) {
            auditShell(session.actorPseudonym(), session.sessionPseudonym(), roles,
                    identity.aggregateVersion(), "DEPENDENCY_UNAVAILABLE",
                    now, traceId, sourceIp);
            throw dependencyUnavailable();
        }
        auditShell(session.actorPseudonym(), session.sessionPseudonym(), roles,
                identity.aggregateVersion(), "ALLOW", now, traceId, sourceIp);
        return projection;
    }

    private void auditShell(
            String actorPseudonym,
            String sessionPseudonym,
            Set<RolePackage> roles,
            long version,
            String result,
            Instant evaluatedAt,
            String traceId,
            String sourceIp) {
        EnumMap<FieldClass, Visibility> fields = new EnumMap<>(FieldClass.class);
        for (FieldClass field : FieldClass.values()) {
            fields.put(field, Visibility.HIDDEN);
        }
        audit.record(new AuthorizationAuditRequest(
                AuthorizationAuditKind.SHELL_VIEW,
                actorPseudonym,
                roles,
                null,
                "authorized-shell",
                sessionPseudonym,
                Set.of(),
                Math.max(1, version),
                fields,
                result,
                evaluatedAt,
                traceId,
                sourceIp));
    }

    private static IdentityAccessException dependencyUnavailable() {
        return new IdentityAccessException(
                "IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE",
                "authorization is temporarily unavailable");
    }
}
