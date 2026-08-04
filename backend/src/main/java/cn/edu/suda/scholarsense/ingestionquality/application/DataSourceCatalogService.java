package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogValidationFailure;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DataSourceCatalogService {
    private static final String FORBIDDEN = "INGESTION_QUALITY_FORBIDDEN";
    private static final String NOT_FOUND = FORBIDDEN;
    private static final String VERSION_CONFLICT = "INGESTION_QUALITY_VERSION_CONFLICT";
    private static final String IDEMPOTENCY_MISMATCH = "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH";
    private final CatalogRepository catalogs;
    private final CatalogIdempotencyPort idempotency;
    private final CatalogContractPolicyPort policy;
    private final CatalogAuthorizationPort authorization;
    private final CatalogTransactionPort transactions;
    private final CatalogAuditPort audit;
    private final CatalogPublicationGuard publicationGuard;

    public DataSourceCatalogService(
            CatalogRepository catalogs,
            CatalogIdempotencyPort idempotency,
            CatalogContractPolicyPort policy,
            CatalogAuthorizationPort authorization,
            CatalogTransactionPort transactions,
            CatalogAuditPort audit,
            CatalogPublicationGuard publicationGuard) {
        this.catalogs = Objects.requireNonNull(catalogs);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.policy = Objects.requireNonNull(policy);
        this.authorization = Objects.requireNonNull(authorization);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
        this.publicationGuard = Objects.requireNonNull(publicationGuard);
    }

    public CatalogView validate(ValidateCatalogCommand command) {
        Objects.requireNonNull(command);
        DataSourceCatalog authorized = load(command.catalogId());
        authorize(command.actorRef(), "data-quality.reconcile", authorized, command.traceId());
        return (CatalogView) transactions.execute(() -> {
            DataSourceCatalog current = load(command.catalogId());
            requireVersion(current, command.expectedVersion());
            List<CatalogValidationFailure> failures = policy.validate(current).stream()
                    .map(item -> new CatalogValidationFailure(item.code(), item.fieldPath()))
                    .toList();
            DataSourceCatalog validated = current.validated(failures, command.requestedAt());
            save(validated, current.aggregateVersion());
            audit.append(new CatalogAuditEvent(
                    "data-source-catalog.validate", failures.isEmpty() ? "publishable" : "invalid",
                    current.catalogId(), validated.aggregateVersion(), command.actorRef(),
                    command.traceId(), command.requestedAt()));
            return CatalogView.from(validated);
        });
    }

    public CatalogView publish(PublishCatalogCommand command) {
        Objects.requireNonNull(command);
        DataSourceCatalog authorized = load(command.catalogId());
        authorize(command.actorRef(), "data-quality.repair", authorized, command.traceId());
        publicationGuard.requireAvailable(command.traceId());
        return (CatalogView) transactions.execute(() -> {
            CatalogIdempotencyResult replay = idempotency.find(command.idempotencyKey()).orElse(null);
            if (replay != null) {
                if (!Objects.equals(replay.requestDigest(), command.requestDigest())) {
                    throw new IngestionQualityApplicationException(IDEMPOTENCY_MISMATCH);
                }
                return replay.response();
            }
            DataSourceCatalog current = load(command.catalogId());
            requireVersion(current, command.expectedVersion());
            DataSourceCatalog published = current.publish(
                    command.catalogReleaseId(), command.evidenceSetDigest(), command.requestedAt());
            save(published, current.aggregateVersion());
            CatalogView view = CatalogView.from(published);
            idempotency.save(new CatalogIdempotencyResult(
                    command.idempotencyKey(), command.requestDigest(), view));
            audit.append(new CatalogAuditEvent(
                    "data-source-catalog.publish", "published", current.catalogId(),
                    published.aggregateVersion(), command.actorRef(), command.traceId(),
                    command.requestedAt()));
            return view;
        });
    }

    public CatalogView get(UUID catalogId, String actorRef, String traceId) {
        DataSourceCatalog catalog = load(catalogId);
        authorize(actorRef, "data-quality.read", catalog, traceId);
        return CatalogView.from(catalog);
    }

    public CatalogDetailView getDetail(UUID catalogId, String actorRef, String traceId) {
        DataSourceCatalog catalog = load(catalogId);
        authorize(actorRef, "data-quality.read", catalog, traceId);
        return CatalogDetailView.from(catalog);
    }

    public List<CatalogView> list(int offset, int limit, String actorRef, String traceId) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IngestionQualityApplicationException("INGESTION_QUALITY_PAGE_INVALID");
        }
        return catalogs.list(offset, limit).stream()
                .filter(catalog -> authorization.authorize(
                        actorRef, "data-quality.read", catalog, traceId)
                        == CatalogAuthorizationDecision.ALLOW)
                .map(CatalogView::from)
                .toList();
    }

    private DataSourceCatalog load(UUID catalogId) {
        return catalogs.find(catalogId)
                .orElseThrow(() -> new IngestionQualityApplicationException(NOT_FOUND));
    }

    private void authorize(
            String actorRef, String action, DataSourceCatalog catalog, String traceId) {
        CatalogAuthorizationDecision decision = authorization.authorize(
                actorRef, action, catalog, traceId);
        if (decision == CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
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

    private void save(DataSourceCatalog catalog, long expectedVersion) {
        try {
            catalogs.save(catalog, expectedVersion);
        } catch (CatalogVersionConflictException conflict) {
            throw new IngestionQualityApplicationException(VERSION_CONFLICT, conflict.currentVersion());
        }
    }
}
