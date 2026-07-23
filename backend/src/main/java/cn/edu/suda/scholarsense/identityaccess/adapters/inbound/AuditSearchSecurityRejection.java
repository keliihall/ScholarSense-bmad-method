package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchSecurityAuditPort;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

/** Audits fail-closed security-filter rejections without reading or retaining request bodies. */
final class AuditSearchSecurityRejection {
    private static final String SEARCH_PATH = "/api/v1/audit-records/search";

    private AuditSearchSecurityRejection() {}

    static boolean applies(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && SEARCH_PATH.equals(request.getRequestURI());
    }

    static boolean record(
            HttpServletRequest request,
            HttpServletResponse response,
            AuditSearchSecurityAuditPort audit,
            String reasonCode) throws IOException {
        if (!applies(request)) {
            return true;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String requester = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : authentication != null && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken)
                        ? authentication.getName() : "anonymous-security-boundary";
        String traceId = W3cTraceId.from(
                request.getHeader("Traceparent"),
                request.getMethod() + ":" + request.getRequestURI());
        try {
            audit.recordRejected(requester, reasonCode, traceId);
            return true;
        } catch (RuntimeException unavailable) {
            IdentityErrorResponseWriter.write(
                    request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IDENTITY_DEPENDENCY_UNAVAILABLE");
            return false;
        }
    }
}
