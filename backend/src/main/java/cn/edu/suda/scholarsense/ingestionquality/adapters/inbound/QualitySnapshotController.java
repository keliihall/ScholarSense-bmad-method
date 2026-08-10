package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotMetricView;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(QualitySnapshotQueryService.class)
@Validated
@RequestMapping(value = "/api/v1/quality-snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
public final class QualitySnapshotController {
    private final QualitySnapshotQueryService snapshots;
    private final InternalSessionIdentityPort identities;

    public QualitySnapshotController(
            QualitySnapshotQueryService snapshots,
            InternalSessionIdentityPort identities) {
        this.snapshots = java.util.Objects.requireNonNull(snapshots);
        this.identities = java.util.Objects.requireNonNull(identities);
    }

    @GetMapping
    public ResponseEntity<QualitySnapshotPage> list(
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String overallResult,
            @RequestParam(required = false) String evaluatedFrom,
            @RequestParam(required = false) String evaluatedTo,
            @RequestParam(defaultValue = "evaluatedAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) String afterEvaluatedAt,
            @RequestParam(required = false) UUID afterSnapshotId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        String traceId = DataSourceCatalogController.trace(request);
        QualitySnapshotQueryCriteria criteria;
        try {
            criteria = new QualitySnapshotQueryCriteria(
                    sourceId, overallResult, instant(evaluatedFrom), instant(evaluatedTo),
                    sortField, sortDirection,
                    instant(afterEvaluatedAt), afterSnapshotId, Math.addExact(size, 1));
        } catch (IllegalArgumentException invalid) {
            throw new CatalogRequestException(invalid);
        }
        List<QualitySnapshotView> visible = snapshots.list(
                criteria, actorContext(request, traceId), traceId);
        boolean hasMore = visible.size() > size;
        List<QualitySnapshotView> items = hasMore
                ? List.copyOf(visible.subList(0, size)) : List.copyOf(visible);
        Cursor next = hasMore && !items.isEmpty()
                ? new Cursor(items.getLast().evaluatedAt(), items.getLast().snapshotId()) : null;
        return ok(new QualitySnapshotPage(items, size, hasMore, next));
    }

    @GetMapping("/{snapshotId}")
    public ResponseEntity<QualitySnapshotView> get(
            @PathVariable UUID snapshotId, HttpServletRequest request) {
        requireUuidV7(snapshotId);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(snapshots.get(snapshotId, actorContext(request, traceId), traceId));
    }

    @GetMapping("/{snapshotId}/metrics/{metricId}")
    public ResponseEntity<QualitySnapshotMetricView> getMetric(
            @PathVariable UUID snapshotId,
            @PathVariable @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,127}$") String metricId,
            @RequestParam String formulaId,
            @RequestParam String formulaVersion,
            HttpServletRequest request) {
        requireUuidV7(snapshotId);
        if (metricId == null || !metricId.matches("^[A-Z][A-Z0-9_]{1,127}$")
                || formulaId == null
                || !formulaId.matches("^QMDP-1\\.0\\.0/[A-Za-z0-9._/-]{1,245}$")
                || formulaVersion == null
                || !formulaVersion.matches("^[0-9]+\\.[0-9]+\\.[0-9]+$")) {
            throw new CatalogRequestException();
        }
        String traceId = DataSourceCatalogController.trace(request);
        return ok(snapshots.getMetric(
                snapshotId, metricId, formulaId, formulaVersion,
                actorContext(request, traceId), traceId));
    }

    private QualitySnapshotActorContext actorContext(
            HttpServletRequest request, String traceId) {
        HttpSession session;
        try {
            session = request.getSession(false);
        } catch (IllegalStateException invalidated) {
            throw forbidden();
        }
        if (session == null) throw forbidden();
        InternalSessionIdentity current;
        try {
            current = identities.current(session.getId(), request.getRemoteAddr(), traceId);
        } catch (InternalSessionIdentityException failure) {
            if (failure.reason()
                    == InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE) {
                throw unavailable();
            }
            throw forbidden();
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        if (current == null || !current.authenticated()
                || current.sessionPseudonym() == null || current.sessionPseudonym().isBlank()
                || current.actorPseudonym() == null || current.actorPseudonym().isBlank()) {
            throw forbidden();
        }
        return new QualitySnapshotActorContext(
                current.sessionPseudonym(), current.actorPseudonym(), request.getRemoteAddr());
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException malformed) {
            throw new CatalogRequestException(malformed);
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new CatalogRequestException();
        }
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }

    private static IngestionQualityApplicationException unavailable() {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(body);
    }

    public record QualitySnapshotPage(
            List<QualitySnapshotView> items,
            int size,
            boolean hasMore,
            Cursor nextCursor) {
        public QualitySnapshotPage { items = List.copyOf(items); }
    }

    public record Cursor(Instant evaluatedAt, UUID snapshotId) {}
}
