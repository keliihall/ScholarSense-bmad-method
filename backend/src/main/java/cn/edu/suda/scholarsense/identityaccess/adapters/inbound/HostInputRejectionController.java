package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.HostInputRejectionAuditService;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/host-input-rejections")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class HostInputRejectionController {
    private final HostInputRejectionAuditService audit;

    public HostInputRejectionController(HostInputRejectionAuditService audit) {
        this.audit = audit;
    }

    @PostMapping
    public Map<String, String> reject(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        if (body.size() != 1 || !(body.get("code") instanceof String code)) {
            throw new IdentityAccessException("HOST_MESSAGE_INVALID", "host request is unavailable");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required");
        }
        audit.record(
                session.getId(), code,
                request.getRemoteAddr(),
                traceId(request));
        return Map.of("status", "recorded");
    }

    private static String traceId(HttpServletRequest request) {
        return W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
    }
}
