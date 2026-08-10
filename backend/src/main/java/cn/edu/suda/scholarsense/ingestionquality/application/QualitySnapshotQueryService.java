package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Assessed-snapshot read boundary with request-time authorization and serialization recheck. */
public final class QualitySnapshotQueryService {
    private static final int MINIMUM_RAW_PAGE_SIZE = 21;
    private static final int MAXIMUM_CANDIDATES_PER_REQUEST = 101;
    private static final Map<String, FieldVisibility> REQUIRED_PROJECTION = Map.of(
            "B", FieldVisibility.CLEAR,
            "I", FieldVisibility.HIDDEN,
            "C", FieldVisibility.HIDDEN,
            "S", FieldVisibility.HIDDEN,
            "E", FieldVisibility.CLEAR,
            "N", FieldVisibility.HIDDEN,
            "G", FieldVisibility.CLEAR,
            "T", FieldVisibility.CLEAR);
    private final QualitySnapshotQueryPort snapshots;
    private final CompositeAuthorizationPort authorization;
    private final CompositeAuthorizationRecheckPort recheck;
    private final QualitySnapshotReadAuditPort readAudit;

    public QualitySnapshotQueryService(
            QualitySnapshotQueryPort snapshots,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            QualitySnapshotReadAuditPort readAudit) {
        this.snapshots = java.util.Objects.requireNonNull(snapshots);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.recheck = java.util.Objects.requireNonNull(recheck);
        this.readAudit = java.util.Objects.requireNonNull(readAudit);
    }

    public List<QualitySnapshotView> list(
            QualitySnapshotQueryCriteria criteria,
            QualitySnapshotActorContext actor,
            String traceId) {
        java.util.Objects.requireNonNull(criteria);
        ArrayList<QualitySnapshotView> visible = new ArrayList<>();
        QualitySnapshotQueryCriteria rawPage = criteriaWithCursor(
                criteria, criteria.afterEvaluatedAt(), criteria.afterSnapshotId(),
                Math.min(MAXIMUM_CANDIDATES_PER_REQUEST,
                        Math.max(criteria.limit(), MINIMUM_RAW_PAGE_SIZE)));
        int examined = 0;
        while (visible.size() < criteria.limit()) {
            List<QualitySnapshot> candidates = safeCandidates(rawPage);
            if (candidates.size() > rawPage.limit()) throw unavailable();
            for (QualitySnapshot candidate : candidates) {
                examined++;
                Authorized authorized = authorize(candidate, actor, traceId, false, false);
                if (authorized != null) {
                    visible.add(QualitySnapshotView.from(authorized.snapshot()));
                    if (visible.size() == criteria.limit()) return List.copyOf(visible);
                }
            }
            if (candidates.size() < rawPage.limit()) return List.copyOf(visible);
            if (examined >= MAXIMUM_CANDIDATES_PER_REQUEST) throw unavailable();
            QualitySnapshot last = candidates.getLast();
            rawPage = criteriaWithCursor(
                    criteria, last.evaluatedAt(), last.snapshotId(),
                    Math.min(rawPage.limit(), MAXIMUM_CANDIDATES_PER_REQUEST - examined));
        }
        return List.copyOf(visible);
    }

    private static QualitySnapshotQueryCriteria criteriaWithCursor(
            QualitySnapshotQueryCriteria base,
            java.time.Instant afterEvaluatedAt,
            UUID afterSnapshotId,
            int limit) {
        return new QualitySnapshotQueryCriteria(
                base.sourceId(), base.overallResult(), base.evaluatedFrom(), base.evaluatedTo(),
                base.sortField(), base.sortDirection(), afterEvaluatedAt, afterSnapshotId, limit);
    }

    public QualitySnapshotView get(
            UUID snapshotId, QualitySnapshotActorContext actor, String traceId) {
        return QualitySnapshotView.from(getAuthorized(
                snapshotId, actor, traceId, "quality-snapshot-detail-read"));
    }

    public QualitySnapshotMetricView getMetric(
            UUID snapshotId,
            String metricId,
            String formulaId,
            String formulaVersion,
            QualitySnapshotActorContext actor,
            String traceId) {
        QualitySnapshot snapshot = getAuthorized(
                snapshotId, actor, traceId, "quality-snapshot-metric-read");
        return snapshot.metricResults().stream()
                .filter(metric -> metric.metricId().equals(metricId)
                        && metric.formulaId().equals(formulaId)
                        && metric.formulaVersion().equals(formulaVersion))
                .findFirst().map(QualitySnapshotMetricView::from).orElseThrow(
                        QualitySnapshotQueryService::forbidden);
    }

    private QualitySnapshot getAuthorized(
            UUID snapshotId,
            QualitySnapshotActorContext actor,
            String traceId,
            String auditAction) {
        requireUuidV7(snapshotId);
        QualitySnapshot candidate = safeFind(snapshotId).orElseThrow(
                QualitySnapshotQueryService::forbidden);
        Authorized authorized = authorize(candidate, actor, traceId, true, true);
        if (authorized == null) throw forbidden();
        try {
            readAudit.record(authorized.snapshot(), actor, auditAction, traceId);
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        return authorized.snapshot();
    }

    private Authorized authorize(
            QualitySnapshot candidate,
            QualitySnapshotActorContext actor,
            String traceId,
            boolean concealDeny,
            boolean refreshBeforeSerialization) {
        CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                actor.authorizationSessionRef(), "QUALITY_SNAPSHOT", "data-quality.read",
                digest(candidate.snapshotId()), candidate.aggregateVersion(),
                Optional.empty(), Optional.empty(), traceId);
        CompositeAuthorizationDecision decision;
        try {
            decision = authorization.authorize(request);
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        if (decision == null
                || decision.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
            throw unavailable();
        }
        if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW
                || decision.objectVersion() != candidate.aggregateVersion()
                || !decision.scopeAnchorSummary().contains("OWNED_SOURCE")) {
            if (concealDeny) throw forbidden();
            return null;
        }
        if (!decision.fieldProjectionSummary().equals(REQUIRED_PROJECTION)) {
            throw unavailable();
        }

        // List candidates are already hydrated in one immutable-snapshot page query. Detail
        // reads still refresh after authorization because their first lookup precedes it.
        QualitySnapshot refreshed = refreshBeforeSerialization
                ? safeFind(candidate.snapshotId()).orElseThrow(
                        QualitySnapshotQueryService::forbidden)
                : candidate;
        if (refreshed.aggregateVersion() != candidate.aggregateVersion()
                || !refreshed.sourceId().equals(candidate.sourceId())
                || !refreshed.immutableHash().equals(candidate.immutableHash())) {
            throw forbidden();
        }
        try {
            var current = recheck.recheck(new CompositeAuthorizationRecheckRequest(
                    request, decision.decisionToken()));
            if (current == null
                    || current.outcome() == CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE) {
                throw unavailable();
            }
            if (current.outcome() != CompositeAuthorizationRecheckOutcome.CURRENT) {
                throw forbidden();
            }
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        return new Authorized(refreshed);
    }

    private List<QualitySnapshot> safeCandidates(QualitySnapshotQueryCriteria criteria) {
        try {
            List<QualitySnapshot> result = snapshots.findAssessed(criteria);
            return List.copyOf(java.util.Objects.requireNonNull(result));
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private Optional<QualitySnapshot> safeFind(UUID snapshotId) {
        try {
            return java.util.Objects.requireNonNull(snapshots.findById(snapshotId));
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    public static String digest(UUID value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw forbidden();
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }

    private static IngestionQualityApplicationException unavailable() {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }

    private record Authorized(QualitySnapshot snapshot) {}
}
