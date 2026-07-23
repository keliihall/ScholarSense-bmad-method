package cn.edu.suda.scholarsense.runtime;

import cn.edu.suda.scholarsense.auditoperations.application.AuditFieldVisibility;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchAuthorizationGateway;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchTokenGateway;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import cn.edu.suda.scholarsense.auditoperations.application.AuditTokenQueryDomain;
import cn.edu.suda.scholarsense.auditoperations.application.AuditTokenQueryValue;
import cn.edu.suda.scholarsense.auditoperations.application.AuthorizedAuditSearchDecision;
import cn.edu.suda.scholarsense.auditoperations.application.SearchAuditEvent;
import cn.edu.suda.scholarsense.auditoperations.application.SearchAuditPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchSecurityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchCsrfProofPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Infrastructure-only bridge keeps both business modules acyclic and their public contracts explicit. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public class AuditSearchIdentityBridgeConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuditSearchAuthorizationGateway.class)
    AuditSearchAuthorizationGateway auditSearchAuthorizationGateway(AuditSearchAuthorizationPort identity) {
        return request -> {
            var decision = identity.authorize(new AuditSearchAuthorizationRequest(
                    request.sessionPseudonym(),
                    request.view() == cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView.BUSINESS
                            ? cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView.BUSINESS
                            : cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView.TECHNICAL,
                    request.objectType(), request.scope(), request.traceId()));
            var fields = new LinkedHashMap<String, AuditFieldVisibility>();
            decision.fieldProjection().forEach((key, value) ->
                    fields.put(key, AuditFieldVisibility.valueOf(value.name())));
            return new AuthorizedAuditSearchDecision(
                    decision.allowed(), decision.rfpVersion(), decision.action(),
                    decision.scopes(), fields, decision.reasonCode());
        };
    }

    @Bean
    @ConditionalOnMissingBean(AuditSearchTokenGateway.class)
    AuditSearchTokenGateway auditSearchTokenGateway(AuditSearchTokenQueryPort identity) {
        return request -> identity.query(new AuditSearchTokenQuery(
                        request.domain() == AuditTokenQueryDomain.ACTOR
                                ? AuditSearchTokenDomain.ACTOR : AuditSearchTokenDomain.OBJECT,
                        request.rawReference(), request.retainedFrom()))
                .stream()
                .map(token -> new AuditTokenQueryValue(
                        token.value(), token.profileVersion(), token.keyVersion()))
                .toList();
    }

    @Bean
    @ConditionalOnMissingBean(AuditSearchSecurityAuditPort.class)
    AuditSearchSecurityAuditPort auditSearchSecurityAuditPort(
            SearchAuditPort audit,
            AuditClock clock) {
        String digest = securityBoundaryDigest();
        return (requesterKey, reasonCode, traceId) -> audit.commit(new SearchAuditEvent(
                requesterKey,
                "audit.search-security-boundary",
                "rejected",
                reasonCode,
                List.of("securityBoundary"),
                digest,
                null,
                traceId,
                clock.now()));
    }

    @Bean
    @ConditionalOnMissingBean(AuditSearchCsrfProofPort.class)
    AuditSearchCsrfProofPort auditSearchCsrfProofPort(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            AuditClock clock) {
        return new JdbcAuditSearchCsrfProofAdapter(
                jdbc,
                new org.springframework.transaction.support.TransactionTemplate(manager),
                clock);
    }

    private static String securityBoundaryDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("securityBoundary".getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
