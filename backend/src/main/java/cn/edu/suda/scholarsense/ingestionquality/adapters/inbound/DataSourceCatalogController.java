package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogDetailView;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.ValidateCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(DataSourceCatalogService.class)
@Validated
@RequestMapping(value = "/api/v1/data-source-catalogs", produces = MediaType.APPLICATION_JSON_VALUE)
public final class DataSourceCatalogController {
    private static final String TRACE_ID_ATTRIBUTE =
            DataSourceCatalogController.class.getName() + ".traceId";
    private final DataSourceCatalogService catalogs;
    private final InternalSessionIdentityPort identities;

    public DataSourceCatalogController(
            DataSourceCatalogService catalogs, InternalSessionIdentityPort identities) {
        this.catalogs = java.util.Objects.requireNonNull(catalogs);
        this.identities = java.util.Objects.requireNonNull(identities);
    }

    @GetMapping
    public ResponseEntity<CatalogPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        String traceId = trace(request);
        CatalogActorContext actor = actorContext(request, traceId);
        int offset;
        try {
            offset = Math.multiplyExact(page, size);
        } catch (ArithmeticException overflow) {
            throw new CatalogRequestException(overflow);
        }
        List<CatalogView> items = catalogs.list(offset, size, actor, traceId);
        boolean hasMore = items.size() > size;
        List<CatalogView> pageItems = hasMore ? List.copyOf(items.subList(0, size)) : items;
        return ok(new CatalogPage(pageItems, page, size, hasMore));
    }

    @GetMapping("/{catalogId}")
    public ResponseEntity<CatalogDetailView> get(
            @PathVariable UUID catalogId, HttpServletRequest request) {
        requireUuidV7(catalogId);
        String traceId = trace(request);
        return ok(catalogs.getDetail(
                catalogId, actorContext(request, traceId), traceId));
    }

    @PostMapping(value = "/{catalogId}/validations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogView> validate(
            @PathVariable UUID catalogId,
            @Valid @RequestBody ValidateRequest body,
            HttpServletRequest request) {
        requireUuidV7(catalogId);
        String traceId = trace(request);
        return ok(catalogs.validate(new ValidateCatalogCommand(
                catalogId, body.expectedVersion(),
                actorContext(request, traceId), traceId)));
    }

    @PostMapping(value = "/{catalogId}/publications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogView> publish(
            @PathVariable UUID catalogId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PublishRequest body,
            HttpServletRequest request) {
        requireUuidV7(catalogId);
        requireUuidV7(body.catalogReleaseId());
        if (idempotencyKey.length() > 128) {
            throw new CatalogRequestException();
        }
        String digest = requestDigest(catalogId, body);
        String traceId = trace(request);
        return ok(catalogs.publish(new PublishCatalogCommand(
                catalogId, body.expectedVersion(), body.expectedCurrentVersion(),
                body.catalogReleaseId(), idempotencyKey, digest,
                actorContext(request, traceId), traceId)));
    }

    private CatalogActorContext actorContext(HttpServletRequest request, String traceId) {
        HttpSession httpSession;
        try {
            httpSession = request.getSession(false);
        } catch (IllegalStateException invalidated) {
            throw forbidden();
        }
        if (httpSession == null) throw forbidden();

        InternalSessionIdentity current;
        try {
            current = identities.current(
                    httpSession.getId(), request.getRemoteAddr(), traceId);
        } catch (InternalSessionIdentityException failure) {
            if (failure.reason()
                    == InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
            }
            throw forbidden();
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
        }
        if (current == null || !current.authenticated()
                || current.sessionPseudonym() == null
                || current.sessionPseudonym().isBlank()
                || current.actorPseudonym() == null
                || current.actorPseudonym().isBlank()) {
            throw forbidden();
        }
        return new CatalogActorContext(
                current.sessionPseudonym(), current.actorPseudonym(), request.getRemoteAddr());
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }

    static String trace(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (existing instanceof String traceId
                && traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            return traceId;
        }
        String traceId = W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new CatalogRequestException();
        }
    }

    private static String requestDigest(UUID catalogId, PublishRequest body) {
        String canonical = catalogId + "\0" + body.expectedVersion() + "\0"
                + body.expectedCurrentVersion() + "\0" + body.catalogReleaseId();
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(body);
    }

    public record ValidateRequest(
            @Min(1) @Max(DataSourceCatalog.MAX_VERSION)
                    long expectedVersion) {}

    public record PublishRequest(
            @Min(1) @Max(DataSourceCatalog.MAX_VERSION)
                    long expectedVersion,
            @Min(0) @Max(DataSourceCatalog.MAX_VERSION)
                    long expectedCurrentVersion,
            @NotNull UUID catalogReleaseId) {}

    public record CatalogPage(List<CatalogView> items, int page, int size, boolean hasMore) {
        public CatalogPage { items = List.copyOf(items); }
    }
}
