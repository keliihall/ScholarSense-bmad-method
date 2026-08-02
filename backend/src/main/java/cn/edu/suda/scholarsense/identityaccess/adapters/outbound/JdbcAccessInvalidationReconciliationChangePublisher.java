package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationAppendCommand;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationEventCodecPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationIdPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationReconciliationChangePublisherPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationStorePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityExceptionAuditTransition;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationResult;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Converts confirmed reconciliation exception transitions into their own
 * stable evidence lineage. It never invents a V2 responsibility lineage.
 */
public final class JdbcAccessInvalidationReconciliationChangePublisher
        implements AccessInvalidationReconciliationChangePublisherPort {
    private static final Duration RETENTION = Duration.ofDays(2190);
    private final JdbcTemplate jdbc;
    private final AccessInvalidationStorePort store;
    private final AccessInvalidationEventCodecPort events;
    private final AccessInvalidationIdPort identifiers;
    private final AccessInvalidationAuditPort audit;

    public JdbcAccessInvalidationReconciliationChangePublisher(
            JdbcTemplate jdbc,
            AccessInvalidationStorePort store,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers) {
        this(jdbc, store, events, identifiers, ignored -> {});
    }

    public JdbcAccessInvalidationReconciliationChangePublisher(
            JdbcTemplate jdbc,
            AccessInvalidationStorePort store,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            AccessInvalidationAuditPort audit) {
        this.jdbc = jdbc;
        this.store = store;
        this.events = events;
        this.identifiers = identifiers;
        this.audit = audit;
    }

    @Override
    public void publish(
            ResponsibilityReconciliationResult result,
            List<ResponsibilityExceptionAuditTransition> transitions) {
        int sequence = 0;
        for (var transition : transitions) {
            Context context = jdbc.query("""
                    select business_key_digest, student_ref_digest
                      from identity_access
                        .ia_responsibility_exception_current
                     where exception_id=?
                    """,
                    (rs, row) -> new Context(
                            rs.getString("business_key_digest"),
                            rs.getString("student_ref_digest")),
                    transition.exceptionId())
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "ACCESS_INVALIDATION_RECONCILIATION_CONTEXT_MISSING"));
            boolean recovery =
                    "responsibility.exception.resolved".equals(
                            transition.action());
            AccessInvalidationReason reason = recovery
                    ? AccessInvalidationReason.RECONCILIATION_RECOVERED
                    : reason(result, context.studentDigest());
            AccessInvalidationLineageId lineage =
                    new AccessInvalidationLineageId(
                            "lin_" + context.businessKeyDigest());
            var head = store.head(lineage.value());
            long version = head.map(
                            AccessInvalidationLineageHead::aggregateVersion)
                    .orElse(0L)
                    + 1;
            var fact = new AccessInvalidationFact(
                    identifiers.next(
                            result.completedAt()
                                    .plusNanos(++sequence)),
                    result.traceId(),
                    recovery
                            ? AccessInvalidationChangeKind.REVALIDATED
                            : AccessInvalidationChangeKind.INVALIDATED,
                    reason,
                    lineage,
                    head.map(AccessInvalidationLineageHead::eventId)
                            .orElse(null),
                    null,
                    AccessInvalidationAggregateType.IDENTITY_CAUSE,
                    "cause_" + context.businessKeyDigest(),
                    version,
                    version,
                    result.completedAt(),
                    sourceVector(result),
                    new AccessInvalidationSubjectSnapshot(
                            "subtok_" + context.studentDigest(),
                            "scptok_" + context.businessKeyDigest(),
                            context.studentDigest(),
                            "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                    new AccessInvalidationAuthorizationSnapshot(
                            recovery
                                    ? AccessInvalidationAuthorizationState
                                            .REVALIDATED
                                    : AccessInvalidationAuthorizationState
                                            .INVALIDATED,
                            recovery,
                            recovery,
                            recovery,
                            recovery,
                            "RFP-1.0.0"),
                    new AccessInvalidationRetention(
                            "restricted",
                            "RS-1.0.0",
                            result.completedAt().plus(RETENTION),
                            false),
                    context.businessKeyDigest());
            store.append(new AccessInvalidationAppendCommand(
                    fact,
                    identifiers.next(result.completedAt()),
                    version - 1,
                    store.fencingToken(lineage.value()),
                    events.encode(fact),
                    result.completedAt()));
            audit.factAppended(fact);
        }
    }

    private static AccessInvalidationReason reason(
            ResponsibilityReconciliationResult result,
            String studentDigest) {
        return result.differences().stream()
                .filter(item -> item.studentSourceRefDigest()
                        .equals(studentDigest))
                .anyMatch(item -> "missing".equals(
                        item.differenceType()))
                ? AccessInvalidationReason.COMPLETE_SNAPSHOT_MISSING
                : AccessInvalidationReason.QUALITY_GATE_INVALID;
    }

    private static AccessInvalidationSourceVector sourceVector(
            ResponsibilityReconciliationResult result) {
        List<AccessInvalidationDependencyWatermark> dependencies =
                new ArrayList<>();
        result.supportingIdentityOrgWatermarks()
                .forEach((route, watermark) -> {
                    String[] parts = route.split("\\|", -1);
                    dependencies.add(
                            new AccessInvalidationDependencyWatermark(
                                    parts[0], parts[1], watermark));
                });
        dependencies.add(
                new AccessInvalidationDependencyWatermark(
                        result.key().feedId(),
                        result.key().partitionId(),
                        result.throughWatermark()));
        return new AccessInvalidationSourceVector(
                "SRC-P0-RESPONSIBILITY-001",
                result.sourceVersion(),
                result.throughWatermark(),
                dependencies);
    }

    private record Context(
            String businessKeyDigest, String studentDigest) {}
}
