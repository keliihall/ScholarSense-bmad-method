package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.RequestOriginPolicy;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RequestOriginValidationFilter extends OncePerRequestFilter {
    private final RequestOriginPolicy policy;

    public RequestOriginValidationFilter(RequestOriginPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> !request.getRequestURI().startsWith("/api/v1/");
        };
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            policy.requireAllowed(request.getHeader("Origin"), request.getHeader("Referer"));
            chain.doFilter(request, response);
        } catch (IdentityAccessException rejected) {
            IdentityErrorResponseWriter.write(
                    request, response, HttpServletResponse.SC_FORBIDDEN,
                    "IDENTITY_SESSION_REQUIRED");
        }
    }
}
