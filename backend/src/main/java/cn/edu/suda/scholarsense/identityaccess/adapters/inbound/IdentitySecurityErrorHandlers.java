package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

final class IdentitySecurityErrorHandlers {
    private IdentitySecurityErrorHandlers() {}

    static AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, failure) -> IdentityErrorResponseWriter.write(
                request, response, HttpServletResponse.SC_UNAUTHORIZED,
                "IDENTITY_SESSION_REQUIRED");
    }

    static AccessDeniedHandler accessDeniedHandler(SessionCommandService commands) {
        return (request, response, failure) -> {
            SessionCommandType type = commandType(request.getRequestURI());
            if (type != null) {
                commands.auditAnonymousRejection(
                        type,
                        request.getHeader("Idempotency-Key"),
                        request.getRemoteAddr(),
                        W3cTraceId.from(
                                request.getHeader("Traceparent"),
                                request.getMethod() + ":" + request.getRequestURI()));
            }
            IdentityErrorResponseWriter.write(
                    request, response, HttpServletResponse.SC_FORBIDDEN,
                    "IDENTITY_SESSION_REQUIRED");
        };
    }

    private static SessionCommandType commandType(String requestUri) {
        if ("/api/v1/identity-sessions/logout".equals(requestUri)) {
            return SessionCommandType.LOGOUT;
        }
        if ("/api/v1/identity-sessions/account-switches".equals(requestUri)) {
            return SessionCommandType.ACCOUNT_SWITCH;
        }
        return null;
    }
}
