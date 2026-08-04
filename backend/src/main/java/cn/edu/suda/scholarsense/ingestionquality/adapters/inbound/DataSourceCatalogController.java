package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogDetailView;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.ValidateCatalogCommand;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.OffsetDateTime;
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
    private final DataSourceCatalogService catalogs;

    public DataSourceCatalogController(DataSourceCatalogService catalogs) {
        this.catalogs = java.util.Objects.requireNonNull(catalogs);
    }

    @GetMapping
    public ResponseEntity<CatalogPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Principal principal,
            HttpServletRequest request) {
        String actor = actor(principal);
        List<CatalogView> items = catalogs.list(Math.multiplyExact(page, size), size, actor, trace(request));
        return ok(new CatalogPage(items, page, size, items.size() == size));
    }

    @GetMapping("/{catalogId}")
    public ResponseEntity<CatalogDetailView> get(
            @PathVariable UUID catalogId, Principal principal, HttpServletRequest request) {
        requireUuidV7(catalogId);
        return ok(catalogs.getDetail(catalogId, actor(principal), trace(request)));
    }

    @PostMapping(value = "/{catalogId}/validations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogView> validate(
            @PathVariable UUID catalogId,
            @Valid @RequestBody ValidateRequest body,
            Principal principal,
            HttpServletRequest request) {
        requireUuidV7(catalogId);
        return ok(catalogs.validate(new ValidateCatalogCommand(
                catalogId, body.expectedVersion(), actor(principal), trace(request),
                body.requestedAt().toInstant())));
    }

    @PostMapping(value = "/{catalogId}/publications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogView> publish(
            @PathVariable UUID catalogId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PublishRequest body,
            Principal principal,
            HttpServletRequest request) {
        requireUuidV7(catalogId);
        requireUuidV7(body.catalogReleaseId());
        if (idempotencyKey.length() > 128 || !body.evidenceSetDigest().matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        String digest = requestDigest(catalogId, body);
        return ok(catalogs.publish(new PublishCatalogCommand(
                catalogId, body.expectedVersion(), body.catalogReleaseId(), idempotencyKey,
                digest, body.evidenceSetDigest(), actor(principal), trace(request),
                body.requestedAt().toInstant())));
    }

    private static String actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
        }
        return principal.getName();
    }

    private static String trace(HttpServletRequest request) {
        return W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("INGESTION_QUALITY_UUID_V7_REQUIRED");
        }
    }

    private static String requestDigest(UUID catalogId, PublishRequest body) {
        String canonical = catalogId + "\0" + body.expectedVersion() + "\0"
                + body.catalogReleaseId() + "\0" + body.evidenceSetDigest();
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

    public record ValidateRequest(@Min(1) long expectedVersion, @NotNull OffsetDateTime requestedAt) {}

    public record PublishRequest(
            @Min(1) long expectedVersion,
            @NotNull UUID catalogReleaseId,
            @NotBlank String evidenceSetDigest,
            @NotNull OffsetDateTime requestedAt) {}

    public record CatalogPage(List<CatalogView> items, int page, int size, boolean hasMore) {
        public CatalogPage { items = List.copyOf(items); }
    }
}
