package cn.edu.suda.scholarsense.auditoperations.adapters;

import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditSearchQueryRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcSearchAuditRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcRetentionExecutionQueryRepository;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchService;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchAuthorizationGateway;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchTokenGateway;
import cn.edu.suda.scholarsense.auditoperations.application.SearchAuditEvent;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionReadService;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.SensitiveProjectionAuditPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Web capability: a read-only availability gate, never collector or verifier schedulers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public class AuditOnlineConfiguration {
    @Bean
    AuditClock auditOnlineClock(TrustedTimeSource trustedTime) {
        return () -> trustedTime.now().instant();
    }

    @Bean
    AuditAvailabilityPort auditOnlineAvailability(
            JdbcTemplate jdbc, ObjectMapper json, AuditClock clock) {
        return new JdbcAuditAvailabilityPort(jdbc, json, clock);
    }

    @Bean
    JdbcAuditSearchQueryRepository auditSearchQueries(JdbcTemplate jdbc) {
        return new JdbcAuditSearchQueryRepository(jdbc);
    }

    @Bean
    JdbcSearchAuditRepository auditSearchAudit(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            AuditSearchTokenGateway tokens,
            TrustedTimeSource time) {
        return new JdbcSearchAuditRepository(
                jdbc, new TransactionTemplate(manager), json, tokens, time);
    }

    AuthorizationObjectEvidenceQueryPort auditSearchObjectEvidence() {
        return query -> {
            boolean business = "AGGREGATE_REPORT".equals(query.objectClass())
                    && "audit.search-business-metadata".equals(query.actionId());
            boolean technical = "TELEMETRY".equals(query.objectClass())
                    && "audit.search-technical-metadata".equals(query.actionId());
            if (!business && !technical) {
                return AuthorizationObjectEvidence.notInstalled();
            }
            AuthorizationScopeAnchor anchor = business
                    ? AuthorizationScopeAnchor.SCHOOL_GOVERNANCE
                    : AuthorizationScopeAnchor.TECHNICAL_OBJECT;
            return new AuthorizationObjectEvidence(
                    AuthorizationEvidenceAvailability.AVAILABLE,
                    Set.of(new AuthorizationScopeEvidence(anchor, null, null)),
                    query.actionId(),
                    Set.of(),
                    null,
                    null,
                    Set.of(),
                    false,
                    1,
                    0,
                    0,
                    query.expectedObjectVersion(),
                    Optional.empty());
        };
    }

    @Bean
    AuthorizationObjectEvidenceProvider auditSearchObjectEvidenceProvider() {
        return AuthorizationObjectEvidenceProvider.forObjectClasses(
                Set.of("AGGREGATE_REPORT", "TELEMETRY"),
                auditSearchObjectEvidence());
    }

    @Bean
    SensitiveProjectionAuditPort auditSearchFieldProjectionAudit(JdbcSearchAuditRepository audit) {
        return record -> {
            boolean projected = "PROJECTED".equals(record.result());
            audit.commit(new SearchAuditEvent(
                record.actorPseudonym(),
                record.actionId(),
                projected ? "accepted" : "rejected",
                projected ? null : "FIELD_PROJECTION_" + record.result(),
                List.of("fieldClassSummary", "projectionCounts", "projectionVersions"),
                digest(String.join("\n",
                        new java.util.TreeMap<>(record.fieldClassSummary()).toString(),
                        Integer.toString(record.clearCount()),
                        Integer.toString(record.maskedCount()),
                        Integer.toString(record.hiddenCount()),
                        record.policyVersion(),
                        record.schemaVersion(),
                        record.keyStateVersion(),
                        record.purposeDigest(),
                        record.result())),
                record.objectVersion(),
                record.traceId(),
                record.trustedTime()));
        };
    }

    @Bean
    AuditSearchService auditSearchService(
            JdbcAuditSearchQueryRepository queries,
            AuditSearchAuthorizationGateway authorization,
            AuditSearchTokenGateway tokens,
            JdbcSearchAuditRepository audit,
            AuditClock clock,
            FieldProjectionPort fieldProjection) {
        return new AuditSearchService(
                queries, authorization, tokens, audit, clock, requesterKey -> requesterKey,
                fieldProjection, "audit-search-v1");
    }

    @Bean
    JdbcRetentionExecutionQueryRepository retentionExecutionQueries(
            JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcRetentionExecutionQueryRepository(jdbc, json);
    }

    @Bean
    RetentionExecutionReadService retentionExecutionReadService(
            JdbcRetentionExecutionQueryRepository executions,
            AuditSearchAuthorizationGateway authorization,
            JdbcSearchAuditRepository audit,
            AuditClock clock) {
        return new RetentionExecutionReadService(executions, authorization, audit, clock);
    }

    private static String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("AUDIT_SEARCH_DIGEST_UNAVAILABLE", impossible);
        }
    }

}
