package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.CurrentAuthorizedShellProjection;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentAuthorizedShellQueryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authorized-shell")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class CurrentAuthorizedShellController {
    private final CurrentAuthorizedShellQueryPort shells;

    public CurrentAuthorizedShellController(CurrentAuthorizedShellQueryPort shells) {
        this.shells = java.util.Objects.requireNonNull(shells);
    }

    @GetMapping
    public ResponseEntity<CurrentAuthorizedShellProjection> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IdentityAccessException(
                    "IDENTITY_SESSION_REQUIRED", "authentication is required");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(shells.current(
                        session.getId(),
                        W3cTraceId.from(
                                request.getHeader("traceparent"), request.getRequestURI()),
                        request.getRemoteAddr()));
    }
}
