package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchSecurityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchCsrfProofPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Consumes each accepted audit-search CSRF proof once, preventing request replay. */
public final class AuditSearchCsrfReplayFilter extends OncePerRequestFilter {
    private final CsrfTokenRepository tokens;
    private final AuditSearchSecurityAuditPort audit;
    private final AuditSearchCsrfProofPort proofs;

    public AuditSearchCsrfReplayFilter(
            CsrfTokenRepository tokens,
            AuditSearchSecurityAuditPort audit,
            AuditSearchCsrfProofPort proofs) {
        this.tokens = java.util.Objects.requireNonNull(tokens);
        this.audit = java.util.Objects.requireNonNull(audit);
        this.proofs = java.util.Objects.requireNonNull(proofs);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuditSearchSecurityRejection.applies(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String proof = request.getHeader("X-CSRF-TOKEN");
        if (proof == null || proof.isBlank()) {
            reject(request, response, "AUDIT_SEARCH_CSRF_PROOF_MISSING");
            return;
        }
        HttpSession session = request.getSession(true);
        boolean accepted;
        try {
            accepted = proofs.consume(digest(session.getId()), digest(proof));
        } catch (RuntimeException unavailable) {
            reject(request, response, "AUDIT_SEARCH_CSRF_PROOF_STORE_UNAVAILABLE");
            return;
        }
        if (!accepted) {
            reject(request, response, "AUDIT_SEARCH_CSRF_PROOF_REPLAYED");
            return;
        }
        tokens.saveToken(null, request, response);
        chain.doFilter(request, response);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String reasonCode) throws IOException {
        if (AuditSearchSecurityRejection.record(request, response, audit, reasonCode)) {
            IdentityErrorResponseWriter.write(
                    request, response, HttpServletResponse.SC_FORBIDDEN,
                    "IDENTITY_SESSION_REQUIRED");
        }
    }

    private static String digest(String proof) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
