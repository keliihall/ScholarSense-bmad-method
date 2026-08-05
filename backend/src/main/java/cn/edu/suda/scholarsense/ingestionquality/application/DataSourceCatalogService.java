package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogValidationFailure;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DataSourceCatalogService {
    private static final String FORBIDDEN = "INGESTION_QUALITY_FORBIDDEN";
    private static final String NOT_FOUND = FORBIDDEN;
    private static final String VERSION_CONFLICT = "INGESTION_QUALITY_VERSION_CONFLICT";
    private static final String IDEMPOTENCY_MISMATCH = "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH";
    private static final String DEPENDENCY_UNAVAILABLE = "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE";
    private static final int MAX_SCAN_BATCH = 101;
    private final CatalogRepository catalogs;
    private final CatalogIdempotencyPort idempotency;
    private final CatalogContractPolicyPort policy;
    private final CatalogAuthorizationPort authorization;
    private final CatalogTransactionPort transactions;
    private final CatalogAuditPort audit;
    private final CatalogPublicationGuard publicationGuard;
    private final CatalogTargetEvidencePort targetEvidence;
    private final DataSourceCatalog authorizationProbe;
    private final TrustedTimeSource time;

    public DataSourceCatalogService(
            CatalogRepository catalogs,
            CatalogIdempotencyPort idempotency,
            CatalogContractPolicyPort policy,
            CatalogAuthorizationPort authorization,
            CatalogTransactionPort transactions,
            CatalogAuditPort audit,
            CatalogPublicationGuard publicationGuard,
            CatalogTargetEvidencePort targetEvidence,
            CatalogAuthorizationProbePort authorizationProbe,
            TrustedTimeSource time) {
        this.catalogs = Objects.requireNonNull(catalogs);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.policy = Objects.requireNonNull(policy);
        this.authorization = Objects.requireNonNull(authorization);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
        this.publicationGuard = Objects.requireNonNull(publicationGuard);
        this.targetEvidence = Objects.requireNonNull(targetEvidence);
        this.authorizationProbe = Objects.requireNonNull(
                Objects.requireNonNull(authorizationProbe).authorizationProbe(),
                "authorization probe");
        this.time = Objects.requireNonNull(time);
    }

    public CatalogView validate(ValidateCatalogCommand command) {
        Objects.requireNonNull(command);
        authorizedLoad(command.catalogId(), command.actorContext().authorizationSessionRef(),
                "data-quality.reconcile", "data-quality.reconcile",
                command.traceId());
        TrustedTime trusted = trustedTime();
        Instant now = trusted.instant();
        return (CatalogView) transactions.execute(() -> {
            DataSourceCatalog current = load(command.catalogId());
            requireVersion(current, command.expectedVersion());
            List<CatalogValidationFailure> failures = policy.validate(current).stream()
                    .map(item -> new CatalogValidationFailure(item.code(), item.fieldPath()))
                    .toList();
            DataSourceCatalog validated = current.validated(failures, now);
            saveValidation(validated, current.aggregateVersion(), command.traceId());
            audit.append(new CatalogAuditEvent(
                    "data-source-catalog.validate", failures.isEmpty() ? "publishable" : "invalid",
                    current.catalogId(), validated.aggregateVersion(),
                    command.actorContext().auditActorRef(), command.actorContext().sourceIp(),
                    command.traceId(), now, trusted.profile(), null));
            return view(validated);
        });
    }

    public CatalogView publish(PublishCatalogCommand command) {
        Objects.requireNonNull(command);
        authorizedLoad(command.catalogId(), command.actorContext().authorizationSessionRef(),
                "data-quality.repair", "data-quality.reconcile",
                command.traceId());
        TrustedTime trusted = trustedTime();
        Instant now = trusted.instant();
        if (command.expectedCurrentVersion() == DataSourceCatalog.MAX_VERSION) {
            throw new IngestionQualityApplicationException(
                    VERSION_CONFLICT, DataSourceCatalog.MAX_VERSION);
        }

        CatalogIdempotencyResult completed = idempotency.find(command.idempotencyKey(), now).orElse(null);
        if (completed != null) return replay(command, completed);
        publicationGuard.requireAvailable(command.traceId());

        try {
            return (CatalogView) transactions.execute(
                    () -> publishInTransaction(command, trusted));
        } catch (CatalogVersionConflictException conflict) {
            throw new IngestionQualityApplicationException(VERSION_CONFLICT, conflict.currentVersion());
        }
    }

    private CatalogView publishInTransaction(
            PublishCatalogCommand command, TrustedTime trusted) {
        Instant now = trusted.instant();
        CatalogIdempotencyClaim claim = idempotency.claim(
                command.idempotencyKey(), command.requestDigest(), command.catalogId(), now);
        if (claim.status() == CatalogIdempotencyClaim.Status.MISMATCH) {
            throw new IngestionQualityApplicationException(IDEMPOTENCY_MISMATCH);
        }
        if (claim.status() == CatalogIdempotencyClaim.Status.REPLAY) {
            return claim.result().response();
        }

        DataSourceCatalog current = load(command.catalogId());
        requireVersion(current, command.expectedVersion());
        if (!policy.validate(current).isEmpty()) {
            throw new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
        }
        CatalogEvidenceSet evidence = targetEvidence.verifiedEvidenceFor(current);
        DataSourceCatalog published = current.publish(
                command.catalogReleaseId(), evidence.digest(), now);
        catalogs.publish(
                published, current.aggregateVersion(), command.expectedCurrentVersion(), evidence);
        CatalogView view = CatalogView.from(published, command.expectedCurrentVersion() + 1);
        idempotency.complete(new CatalogIdempotencyResult(
                command.idempotencyKey(), command.requestDigest(), view), now);
        audit.append(new CatalogAuditEvent(
                "data-source-catalog.publish", "published", current.catalogId(),
                published.aggregateVersion(), command.actorContext().auditActorRef(),
                command.actorContext().sourceIp(), command.traceId(), now,
                trusted.profile(), sha256(command.idempotencyKey())));
        return view;
    }

    private static CatalogView replay(
            PublishCatalogCommand command, CatalogIdempotencyResult completed) {
        if (!Objects.equals(completed.requestDigest(), command.requestDigest())) {
            throw new IngestionQualityApplicationException(IDEMPOTENCY_MISMATCH);
        }
        return completed.response();
    }

    public CatalogView get(UUID catalogId, CatalogActorContext actor, String traceId) {
        DataSourceCatalog catalog = authorizedLoad(
                catalogId, actor.authorizationSessionRef(),
                "data-quality.read", "data-quality.read", traceId);
        return view(catalog);
    }

    public CatalogDetailView getDetail(
            UUID catalogId, CatalogActorContext actor, String traceId) {
        DataSourceCatalog catalog = authorizedLoad(
                catalogId, actor.authorizationSessionRef(),
                "data-quality.read", "data-quality.read", traceId);
        return CatalogDetailView.from(catalog, currentPointerVersion());
    }

    /** Returns at most {@code limit + 1} authorized rows so the transport can derive hasMore. */
    public List<CatalogView> list(
            int offset, int limit, CatalogActorContext actor, String traceId) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IngestionQualityApplicationException("INGESTION_QUALITY_PAGE_INVALID");
        }
        int required = Math.addExact(limit, 1);
        int rawOffset = 0;
        int authorizedOffset = 0;
        long pointerVersion = currentPointerVersion();
        List<CatalogView> visible = new ArrayList<>(required);
        while (visible.size() < required) {
            List<DataSourceCatalog> batch = catalogs.list(rawOffset, MAX_SCAN_BATCH);
            if (batch.isEmpty()) break;
            for (DataSourceCatalog catalog : batch) {
                CatalogAuthorizationDecision decision = authorization.authorize(
                        actor.authorizationSessionRef(), "data-quality.read", "data-quality.read",
                        catalog, traceId);
                if (decision == CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE) {
                    throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
                }
                if (decision == CatalogAuthorizationDecision.ALLOW) {
                    if (authorizedOffset < offset) {
                        authorizedOffset++;
                    } else {
                        visible.add(CatalogView.from(catalog, pointerVersion));
                        if (visible.size() == required) break;
                    }
                }
            }
            if (batch.size() < MAX_SCAN_BATCH) break;
            rawOffset = Math.addExact(rawOffset, batch.size());
        }
        return List.copyOf(visible);
    }

    private DataSourceCatalog load(UUID catalogId) {
        return catalogs.find(catalogId)
                .orElseThrow(() -> new IngestionQualityApplicationException(NOT_FOUND));
    }

    private DataSourceCatalog authorizedLoad(
            UUID catalogId, String actorRef, String sourceAction,
            String dependencyAction, String traceId) {
        DataSourceCatalog existing = catalogs.find(catalogId).orElse(null);
        DataSourceCatalog authorizationObject = existing == null ? authorizationProbe : existing;
        authorize(actorRef, sourceAction, dependencyAction, authorizationObject, traceId);
        if (existing == null) {
            throw new IngestionQualityApplicationException(NOT_FOUND);
        }
        return existing;
    }

    private void authorize(
            String actorRef, String sourceAction, String dependencyAction,
            DataSourceCatalog catalog, String traceId) {
        CatalogAuthorizationDecision decision = authorization.authorize(
                actorRef, sourceAction, dependencyAction, catalog, traceId);
        if (decision == CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE) {
            throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
        }
        if (decision != CatalogAuthorizationDecision.ALLOW) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
    }

    private static void requireVersion(DataSourceCatalog catalog, long expectedVersion) {
        if (catalog.aggregateVersion() != expectedVersion) {
            throw new IngestionQualityApplicationException(VERSION_CONFLICT, catalog.aggregateVersion());
        }
    }

    private void saveValidation(
            DataSourceCatalog catalog, long expectedVersion, String traceId) {
        try {
            catalogs.saveValidation(catalog, expectedVersion, traceId);
        } catch (CatalogVersionConflictException conflict) {
            throw new IngestionQualityApplicationException(
                    VERSION_CONFLICT, conflict.currentVersion());
        }
    }

    private CatalogView view(DataSourceCatalog catalog) {
        return CatalogView.from(catalog, currentPointerVersion());
    }

    private long currentPointerVersion() {
        return catalogs.currentPointer().map(CatalogCurrentPointer::pointerVersion).orElse(0L);
    }

    private TrustedTime trustedTime() {
        try {
            return Objects.requireNonNull(time.now(), "trusted time");
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
