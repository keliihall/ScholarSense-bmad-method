package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityView;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotActorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@ConditionalOnBean(QualityEligibilityQueryService.class)
@Validated
@RequestMapping(value = "/api/v1/quality-eligibilities", produces = MediaType.APPLICATION_JSON_VALUE)
public final class QualityEligibilityController {
    private final QualityEligibilityQueryService eligibilities;
    private final InternalSessionIdentityPort identities;

    public QualityEligibilityController(
            QualityEligibilityQueryService eligibilities,
            InternalSessionIdentityPort identities) {
        this.eligibilities = java.util.Objects.requireNonNull(eligibilities);
        this.identities = java.util.Objects.requireNonNull(identities);
    }

    @GetMapping
    public ResponseEntity<Page> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String afterOccurredAt,
            @RequestParam(required = false) UUID afterEligibilityId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        String traceId = DataSourceCatalogController.trace(request);
        QualityEligibilityQueryCriteria criteria;
        try {
            criteria = new QualityEligibilityQueryCriteria(
                    status, ruleId, instant(afterOccurredAt), afterEligibilityId,
                    Math.addExact(size, 1));
        } catch (IllegalArgumentException invalid) {
            throw new CatalogRequestException(invalid);
        }
        List<QualityEligibilityView> visible = eligibilities.list(
                criteria, actorContext(request, traceId), traceId);
        boolean hasMore = visible.size() > size;
        List<QualityEligibilityView> items = hasMore
                ? List.copyOf(visible.subList(0, size)) : List.copyOf(visible);
        Cursor cursor = hasMore && !items.isEmpty()
                ? new Cursor(items.getLast().occurredAt(), items.getLast().eligibilityId())
                : null;
        return ok(new Page(items, size, hasMore, cursor));
    }

    @GetMapping("/{eligibilityId}")
    public ResponseEntity<QualityEligibilityView> get(
            @PathVariable UUID eligibilityId, HttpServletRequest request) {
        requireUuidV7(eligibilityId);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(eligibilities.get(
                eligibilityId, actorContext(request, traceId), traceId));
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

    public record Page(
            List<QualityEligibilityView> items,
            int size,
            boolean hasMore,
            Cursor nextCursor) {
        public Page { items = List.copyOf(items); }
    }

    public record Cursor(Instant occurredAt, UUID eligibilityId) {}
}
