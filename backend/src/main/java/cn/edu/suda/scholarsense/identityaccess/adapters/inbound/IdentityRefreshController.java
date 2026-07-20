package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.SessionRefresh;
import cn.edu.suda.scholarsense.identityaccess.application.SessionRefreshService;
import cn.edu.suda.scholarsense.identityaccess.domain.RefreshRotation;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.Map;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCookiePolicy;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Refreshes only from server-side token custody; no bearer token is accepted or returned. */
@RestController
@RequestMapping("/api/v1/identity-session-refreshes")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class IdentityRefreshController {
    private final SessionRefreshService refreshes;
    private final String registrationId;

    public IdentityRefreshController(
            SessionRefreshService refreshes,
            @Value("${scholarsense.identity.registration-id}") String registrationId) {
        this.refreshes = refreshes;
        this.registrationId = registrationId;
    }

    @PostMapping
    public Map<String, Object> refresh(
            @Valid @RequestBody RefreshRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            refreshes.rejectMissingSession(request.getRemoteAddr(), traceId(request));
            throw new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required");
        }
        String traceId = traceId(request);
        var rotation = refreshWithFailClosedBrowserSession(
                body, request, response, session, traceId);
        return Map.of(
                "sessionVersion", rotation.session().sessionVersion(),
                "expiresAt", rotation.session().idleExpiresAt().isBefore(rotation.session().absoluteExpiresAt())
                        ? rotation.session().idleExpiresAt() : rotation.session().absoluteExpiresAt(),
                "warningAt", rotation.session().warningAt(),
                "profileVersion", "ISP-1.0.0");
    }

    private RefreshRotation refreshWithFailClosedBrowserSession(
                    RefreshRequest body,
                    HttpServletRequest request,
                    HttpServletResponse response,
                    HttpSession session,
                    String traceId) {
        try {
            return refreshes.refresh(new SessionRefresh(
                    session.getId(),
                    body.sessionVersion(),
                    registrationId,
                    request.getRemoteAddr(),
                    traceId));
        } catch (IdentityAccessException rejected) {
            if ("IDENTITY_REAUTHENTICATION_REQUIRED".equals(rejected.code())) {
                try {
                    session.invalidate();
                } catch (IllegalStateException alreadyInvalidated) {
                    // The original browser session is already unusable.
                }
                response.setHeader(HttpHeaders.SET_COOKIE, SessionCookiePolicy.clear());
            }
            throw rejected;
        }
    }

    private static String traceId(HttpServletRequest request) {
        return W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
    }

    public record RefreshRequest(@Min(1) long sessionVersion) {}
}
