package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapCreated;
import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issues the proof through the authenticated iframe's same-origin BFF, never through the portal. */
@RestController
@RequestMapping("/api/v1/host-bootstrap-issuances")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class HostBootstrapIssuanceController {
    private static final String AUDIENCE = "scholarsense-web";
    private final HostBootstrapService bootstraps;
    private final String applicationOrigin;
    private final String portalOrigin;

    public HostBootstrapIssuanceController(
            HostBootstrapService bootstraps,
            @Value("${scholarsense.identity.application-origin}") String applicationOrigin,
            @Value("${scholarsense.identity.portal-origin}") String portalOrigin) {
        this.bootstraps = bootstraps;
        this.applicationOrigin = applicationOrigin;
        this.portalOrigin = portalOrigin;
    }

    @PostMapping
    public Map<String, Object> issue(
            @RequestHeader("Origin") String requestOrigin,
            HttpServletRequest request) {
        if (!applicationOrigin.equals(requestOrigin)) {
            throw new cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException(
                    "HOST_ORIGIN_FORBIDDEN", "host bootstrap is unavailable");
        }
        HttpSession browserSession = request.getSession(true);
        HostBootstrapCreated created = bootstraps.issue(
                AUDIENCE,
                portalOrigin,
                ContinuationService.digest(BrowserSessionBinding.getOrCreate(browserSession)));
        return Map.of(
                "bootstrapCode", created.bootstrapCode(),
                "audience", created.audience(),
                "origin", created.origin(),
                "expiresAt", created.expiresAt(),
                "profileVersion", "HIP-1.0.0");
    }
}
