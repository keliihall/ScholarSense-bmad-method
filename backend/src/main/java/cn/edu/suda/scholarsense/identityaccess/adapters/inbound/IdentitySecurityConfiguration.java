package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.RequestOriginPolicy;
import cn.edu.suda.scholarsense.identityaccess.application.HostInputRejectionAuditService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchSecurityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchCsrfProofPort;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.OidcSessionEstablishmentService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public class IdentitySecurityConfiguration {
    @Bean
    HostInputRejectionAuditService hostInputRejectionAuditService(
            IdentitySessionRepository sessions,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit) {
        return new HostInputRejectionAuditService(sessions, auditFacts, audit);
    }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClients,
            OidcSessionEstablishmentService sessionEstablishment,
            ContinuationService continuations,
            SessionCommandService sessionCommands,
            AuditSearchSecurityAuditPort auditSearchSecurityAudit,
            AuditSearchCsrfProofPort auditSearchCsrfProofs,
            @Value("${scholarsense.identity.application-origin}") String applicationOrigin)
            throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var successHandler = new OidcLoginSuccessHandler(
                authorizedClients, sessionEstablishment, continuations, applicationOrigin);
        var csrfTokens = new CookieCsrfTokenRepository();
        csrfTokens.setCookieName("__Host-ScholarSense-CSRF");
        csrfTokens.setHeaderName("X-CSRF-TOKEN");
        csrfTokens.setCookieCustomizer(cookie -> cookie
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax"));
        AuthorizationManager<RequestAuthorizationContext> auditSearchRequestGate =
                (authentication, context) -> {
                    var current = authentication.get();
                    return new AuthorizationDecision(
                            current != null && current.isAuthenticated()
                                    && !(current instanceof AnonymousAuthenticationToken));
                };
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health/**", "/oauth2/**", "/login/oauth2/**",
                                "/api/v1/identity-runtime")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/identity-sessions/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit-records/search")
                        .access(auditSearchRequestGate)
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/identity-sessions/logout",
                                "/api/v1/identity-sessions/account-switches",
                                "/api/v1/identity-sessions/reauthentications",
                                "/api/v1/host-bootstrap-issuances",
                                "/api/v1/host-bootstrap-exchanges")
                        .permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2Login(oauth -> oauth.authorizationEndpoint(
                        endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .successHandler(successHandler))
                .oauth2Client(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(
                                IdentitySecurityErrorHandlers.authenticationEntryPoint(
                                        auditSearchSecurityAudit))
                        .accessDeniedHandler(
                                IdentitySecurityErrorHandlers.accessDeniedHandler(
                                        sessionCommands, auditSearchSecurityAudit)))
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                .addFilterBefore(
                        new RequestOriginValidationFilter(
                                new RequestOriginPolicy(applicationOrigin),
                                auditSearchSecurityAudit),
                        CsrfFilter.class)
                .addFilterAfter(
                        new AuditSearchCsrfReplayFilter(
                                csrfTokens, auditSearchSecurityAudit, auditSearchCsrfProofs),
                        CsrfFilter.class);
        return http.build();
    }
}
