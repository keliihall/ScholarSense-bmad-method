package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchPage;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchService;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-records")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class AuditSearchController {
    private final AuditSearchService searches;

    public AuditSearchController(AuditSearchService searches) {
        this.searches = java.util.Objects.requireNonNull(searches);
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuditSearchPage> search(
            @Valid @RequestBody SearchRequest body,
            Principal principal,
            HttpServletRequest request) {
        String traceId = W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new cn.edu.suda.scholarsense.auditoperations.application.AuditSearchException(
                    "AUDIT_SEARCH_FORBIDDEN");
        }
        AuditSearchCriteria criteria = new AuditSearchCriteria(
                principal.getName(),
                view(body.view()),
                body.actorRef(),
                body.objectType(),
                body.objectRef(),
                body.action(),
                parseInstant(body.occurredFrom()),
                parseInstant(body.occurredTo()),
                body.outcome(),
                body.traceId(),
                body.page(),
                body.size(),
                body.asOfSequence(),
                traceId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(searches.search(criteria));
    }

    private static AuditSearchView view(String value) {
        return switch (value) {
            case "business" -> AuditSearchView.BUSINESS;
            case "technical" -> AuditSearchView.TECHNICAL;
            default -> throw new IllegalArgumentException("AUDIT_SEARCH_VIEW_INVALID");
        };
    }

    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TIME_INVALID");
        }
    }

    public record SearchRequest(
            @NotBlank String view,
            String actorRef,
            String objectType,
            String objectRef,
            String action,
            String occurredFrom,
            String occurredTo,
            String outcome,
            String traceId,
            @Min(0) int page,
            @Min(1) @Max(100) int size,
            Long asOfSequence) {}
}
