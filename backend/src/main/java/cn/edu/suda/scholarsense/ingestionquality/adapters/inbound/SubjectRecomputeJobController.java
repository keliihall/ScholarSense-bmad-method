package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.RecomputeJobActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.RecomputeJobView;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectRecomputeJobQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(SubjectRecomputeJobQueryService.class)
@RequestMapping(value = "/api/v1/subject-recompute-jobs",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class SubjectRecomputeJobController {
    private final SubjectRecomputeJobQueryService service;
    private final InternalSessionIdentityPort identities;

    public SubjectRecomputeJobController(
            SubjectRecomputeJobQueryService service, InternalSessionIdentityPort identities) {
        this.service = java.util.Objects.requireNonNull(service);
        this.identities = java.util.Objects.requireNonNull(identities);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<RecomputeJobView> get(
            @PathVariable UUID jobId, HttpServletRequest request) {
        requireUuidV7(jobId);
        String traceId = DataSourceCatalogController.trace(request);
        RecomputeJobActorContext actor = actor(request, traceId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(service.get(jobId, actor, traceId));
    }

    private RecomputeJobActorContext actor(HttpServletRequest request, String traceId) {
        HttpSession session;
        try {
            session = request.getSession(false);
        } catch (IllegalStateException invalidated) {
            throw forbidden();
        }
        if (session == null) throw forbidden();
        InternalSessionIdentity identity;
        try {
            identity = identities.current(session.getId(), request.getRemoteAddr(), traceId);
        } catch (InternalSessionIdentityException failure) {
            if (failure.reason() == InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
            }
            throw forbidden();
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
        }
        if (identity == null || !identity.authenticated()
                || identity.actorPseudonym() == null || identity.actorPseudonym().isBlank()) {
            throw forbidden();
        }
        return new RecomputeJobActorContext(identity.actorPseudonym(), request.getRemoteAddr());
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new CatalogRequestException();
        }
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }
}
