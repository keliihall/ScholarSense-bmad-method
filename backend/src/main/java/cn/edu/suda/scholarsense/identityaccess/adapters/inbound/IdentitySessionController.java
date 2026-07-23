package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.ContinuationCreated;
import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionProjection;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommand;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandResult;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCookiePolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity-sessions")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class IdentitySessionController {
    private static final String CONTINUATION_ATTRIBUTE = "identity.continuation";
    private final CurrentSessionService currentSessions;
    private final ContinuationService continuations;
    private final SessionCommandService commands;

    public IdentitySessionController(
            CurrentSessionService currentSessions,
            ContinuationService continuations,
            SessionCommandService commands) {
        this.currentSessions = currentSessions;
        this.continuations = continuations;
        this.commands = commands;
    }

    @GetMapping("/current")
    public CurrentSessionProjection current(HttpServletRequest request) {
        return currentSessions.current(
                requiredSession(request).getId(), request.getRemoteAddr(), traceId(request));
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/reauthentications")
    public Map<String, Object> reauthenticate(
            @Valid @RequestBody ReauthenticationRequest body,
            @RequestHeader("Origin") String origin,
            HttpServletRequest request) {
        if (!origin.equals(body.origin())) {
            throw new cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException(
                    "CONTINUATION_INVALID_OR_EXPIRED", "continuation binding is invalid");
        }
        HttpSession session = request.getSession(true);
        ContinuationCreated continuation = continuations.create(
                BrowserSessionBinding.getOrCreate(session), body.origin(),
                body.targetRouteId(), body.opaqueContext());
        session.setAttribute(CONTINUATION_ATTRIBUTE, continuation.continuationCode());
        return Map.of(
                "continuationCode", continuation.continuationCode(),
                "expiresAt", continuation.expiresAt(),
                "authorizationUri", "/oauth2/authorization/school-idp");
    }

    @PostMapping("/logout")
    public ResponseEntity<SessionCommandResult> logout(
            @Valid @RequestBody SessionCommandRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return finish(SessionCommandType.LOGOUT, body, idempotencyKey, request, response);
    }

    @PostMapping("/account-switches")
    public ResponseEntity<SessionCommandResult> accountSwitch(
            @Valid @RequestBody SessionCommandRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return finish(SessionCommandType.ACCOUNT_SWITCH, body, idempotencyKey, request, response);
    }

    private ResponseEntity<SessionCommandResult> finish(
            SessionCommandType type,
            SessionCommandRequest body,
            String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestDigest = digest(type.name() + ":" + body.sessionVersion());
        String sourceIp = request.getRemoteAddr();
        String traceId = traceId(request);
        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            SessionCommandResult replay = commands.replayCompleted(
                    type, idempotencyKey, requestDigest, sourceIp, traceId)
                    .orElseThrow(() -> new cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException(
                            "IDENTITY_SESSION_REQUIRED", "authentication is required"));
            response.setHeader(HttpHeaders.SET_COOKIE, SessionCookiePolicy.clear());
            return ResponseEntity.ok(replay);
        }
        String sessionId = httpSession.getId();
        SessionCommandResult result = commands.execute(new SessionCommand(
                type,
                sessionId,
                body.sessionVersion(),
                idempotencyKey,
                requestDigest,
                sourceIp,
                traceId));
        httpSession.invalidate();
        response.setHeader(HttpHeaders.SET_COOKIE, SessionCookiePolicy.clear());
        return ResponseEntity.ok(result);
    }

    private static HttpSession requiredSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException(
                    "IDENTITY_SESSION_REQUIRED", "authentication is required");
        }
        return session;
    }

    private static String traceId(HttpServletRequest request) {
        return W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record ReauthenticationRequest(
            @NotBlank @Pattern(regexp = "(?:shell\\.(?:home|session)|audit\\.search)") String targetRouteId,
            @NotBlank @Pattern(regexp = "https://[A-Za-z0-9.-]+") String origin,
            @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String opaqueContext) {}

    public record SessionCommandRequest(@Min(1) long sessionVersion) {}
}
