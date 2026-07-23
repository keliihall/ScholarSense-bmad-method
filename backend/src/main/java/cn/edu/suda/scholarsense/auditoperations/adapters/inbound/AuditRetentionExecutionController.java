package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchException;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionEvidenceView;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionReadService;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class AuditRetentionExecutionController {
    private final RetentionExecutionReadService reads;

    public AuditRetentionExecutionController(RetentionExecutionReadService reads) {
        this.reads = java.util.Objects.requireNonNull(reads);
    }

    @GetMapping(value = "/api/v1/audit-retention-executions/{executionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RetentionExecutionEvidenceView> read(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "business") String view,
            Principal principal,
            HttpServletRequest request) {
        String traceId = W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new AuditSearchException("AUDIT_EVIDENCE_NOT_AVAILABLE");
        }
        UUID id;
        try {
            id = UUID.fromString(executionId);
        } catch (IllegalArgumentException invalid) {
            throw new AuditSearchException("AUDIT_EVIDENCE_NOT_AVAILABLE");
        }
        AuditSearchView requestedView = switch (view) {
            case "business" -> AuditSearchView.BUSINESS;
            case "technical" -> AuditSearchView.TECHNICAL;
            default -> throw new AuditSearchException("AUDIT_EVIDENCE_NOT_AVAILABLE");
        };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(reads.read(id, requestedView, principal.getName(), traceId));
    }
}
