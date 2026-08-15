package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskView;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationView;
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
import org.springframework.beans.factory.annotation.Autowired;
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
@ConditionalOnBean(QualityRecoveryTaskQueryService.class)
@Validated
@RequestMapping(
        value = "/api/v1/quality-recovery-tasks",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class QualityRecoveryTaskController {
    private final QualityRecoveryTaskQueryService tasks;
    private final InternalSessionIdentityPort identities;
    private final RecoveryObservationQueryService observations;

    public QualityRecoveryTaskController(
            QualityRecoveryTaskQueryService tasks,
            InternalSessionIdentityPort identities) {
        this(tasks, identities, null);
    }

    @Autowired
    public QualityRecoveryTaskController(
            QualityRecoveryTaskQueryService tasks,
            InternalSessionIdentityPort identities,
            RecoveryObservationQueryService observations) {
        this.tasks = java.util.Objects.requireNonNull(tasks);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.observations = observations;
    }

    @GetMapping
    public ResponseEntity<Page> list(
            @RequestParam String sourceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String afterOccurredAt,
            @RequestParam(required = false) UUID afterTaskId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        String traceId = DataSourceCatalogController.trace(request);
        QualityRecoveryTaskQueryCriteria criteria;
        try {
            criteria = new QualityRecoveryTaskQueryCriteria(
                    sourceId, status, instant(afterOccurredAt), afterTaskId,
                    Math.addExact(size, 1));
        } catch (IllegalArgumentException invalid) {
            throw new CatalogRequestException(invalid);
        }
        List<QualityRecoveryTaskView> visible = tasks.list(
                criteria, actorContext(request, traceId), traceId);
        boolean hasMore = visible.size() > size;
        List<QualityRecoveryTaskView> items = hasMore
                ? List.copyOf(visible.subList(0, size)) : List.copyOf(visible);
        Cursor cursor = hasMore && !items.isEmpty()
                ? new Cursor(items.getLast().occurredAt(), items.getLast().taskId())
                : null;
        return ok(new Page(items, size, hasMore, cursor));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<QualityRecoveryTaskView> get(
            @PathVariable UUID taskId, HttpServletRequest request) {
        requireUuidV7(taskId);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(tasks.get(taskId, actorContext(request, traceId), traceId));
    }

    @GetMapping("/{taskId}/observation")
    public ResponseEntity<RecoveryObservationView> observation(
            @PathVariable UUID taskId, HttpServletRequest request) {
        requireUuidV7(taskId);
        if (observations == null) throw unavailable();
        String traceId = DataSourceCatalogController.trace(request);
        return ok(observations.get(taskId, actorContext(request, traceId), traceId));
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
            List<QualityRecoveryTaskView> items,
            int size,
            boolean hasMore,
            Cursor nextCursor) {
        public Page { items = List.copyOf(items); }
    }

    public record Cursor(Instant occurredAt, UUID taskId) {}
}
