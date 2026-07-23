package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchSecurityAuditPort;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

final class IdentitySecurityErrorHandlers {
    private IdentitySecurityErrorHandlers() {}

    static AuthenticationEntryPoint authenticationEntryPoint(AuditSearchSecurityAuditPort audit) {
        return (request, response, failure) -> {
            if (AuditSearchSecurityRejection.record(
                    request, response, audit, "AUDIT_SEARCH_AUTHENTICATION_REQUIRED")) {
                IdentityErrorResponseWriter.write(
                        request, response, HttpServletResponse.SC_UNAUTHORIZED,
                        "IDENTITY_SESSION_REQUIRED");
            }
        };
    }

    static AccessDeniedHandler accessDeniedHandler(
            SessionCommandService commands,
            AuditSearchSecurityAuditPort audit) {
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
            if (AuditSearchSecurityRejection.record(
                    request, response, audit, "AUDIT_SEARCH_REQUEST_REJECTED")) {
                IdentityErrorResponseWriter.write(
                        request, response, HttpServletResponse.SC_FORBIDDEN,
                        "IDENTITY_SESSION_REQUIRED");
            }
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
