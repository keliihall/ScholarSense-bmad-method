package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.ContinuationTarget;
import cn.edu.suda.scholarsense.identityaccess.application.OidcSessionEstablishment;
import cn.edu.suda.scholarsense.identityaccess.application.OidcSessionEstablishmentService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCookiePolicy;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

public final class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final OAuth2AuthorizedClientService authorizedClients;
    private final OidcSessionEstablishmentService sessions;
    private final ContinuationService continuations;
    private final String applicationOrigin;

    public OidcLoginSuccessHandler(
            OAuth2AuthorizedClientService authorizedClients,
            OidcSessionEstablishmentService sessions,
            ContinuationService continuations,
            String applicationOrigin) {
        this.authorizedClients = authorizedClients;
        this.sessions = sessions;
        this.continuations = continuations;
        this.applicationOrigin = applicationOrigin;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        try {
            if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                    || !(oauth.getPrincipal() instanceof OidcUser user)) {
                throw new IllegalStateException("IDENTITY_OIDC_PRINCIPAL_INVALID");
            }
            var client = authorizedClients.loadAuthorizedClient(
                    oauth.getAuthorizedClientRegistrationId(), oauth.getName());
            if (client == null || client.getRefreshToken() == null
                    || client.getAccessToken().getExpiresAt() == null || user.getIssuer() == null) {
                throw new IllegalStateException("IDENTITY_OIDC_TOKENS_INCOMPLETE");
            }
            var httpSession = request.getSession(true);
            String browserBinding = BrowserSessionBinding.getOrCreate(httpSession);
            Object continuation = httpSession.getAttribute("identity.continuation");
            String continuationCode = continuation instanceof String code ? code : null;
            // Clear fallible browser state before committing the server-side identity session.
            httpSession.removeAttribute("identity.continuation");
            ContinuationTarget resolved = sessions.establishAndConsume(new OidcSessionEstablishment(
                    httpSession.getId(), user.getIssuer().toString(), user.getSubject(),
                    oauth.getAuthorizedClientRegistrationId(), client.getAccessToken().getTokenValue(),
                    client.getRefreshToken().getTokenValue(), client.getAccessToken().getExpiresAt(),
                    browserBinding, applicationOrigin, request.getRemoteAddr(), traceId(request)),
                    continuations, continuationCode);

            String target = "/scholarsense/";
            if (resolved != null) {
                target = switch (resolved.routeId()) {
                    case "shell.session" -> "/scholarsense/session";
                    case "audit.search" -> "/scholarsense/audit/search";
                    default -> "/scholarsense/";
                };
            }
            response.sendRedirect(target);
        } catch (RuntimeException failure) {
            cleanupFailedAuthentication(request, response, authentication);
            if (!response.isCommitted()) {
                response.resetBuffer();
            }
            IdentityErrorResponseWriter.write(
                    request,
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IDENTITY_DEPENDENCY_UNAVAILABLE");
        }
    }

    private void cleanupFailedAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        SecurityContextHolder.clearContext();
        if (authentication instanceof OAuth2AuthenticationToken oauth) {
            try {
                authorizedClients.removeAuthorizedClient(
                        oauth.getAuthorizedClientRegistrationId(), oauth.getName());
            } catch (RuntimeException ignored) {
                // Continue clearing the remaining bearer state even if client cleanup is unavailable.
            }
        }
        try {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        } catch (IllegalStateException ignored) {
            // The session was already invalidated by another cleanup participant.
        }
        response.setHeader(HttpHeaders.SET_COOKIE, SessionCookiePolicy.clear());
    }

    private static String traceId(HttpServletRequest request) {
        return W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
    }
}
