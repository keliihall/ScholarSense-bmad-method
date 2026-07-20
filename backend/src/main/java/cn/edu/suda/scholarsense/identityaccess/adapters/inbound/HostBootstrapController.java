package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapService;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/host-bootstrap-exchanges")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class HostBootstrapController {
    private final HostBootstrapService bootstraps;
    private final String applicationOrigin;
    private final String portalOrigin;

    public HostBootstrapController(
            HostBootstrapService bootstraps,
            @Value("${scholarsense.identity.application-origin}") String applicationOrigin,
            @Value("${scholarsense.identity.portal-origin}") String portalOrigin) {
        this.bootstraps = bootstraps;
        this.applicationOrigin = applicationOrigin;
        this.portalOrigin = portalOrigin;
    }

    @PostMapping
    public Map<String, String> exchange(
            @Valid @RequestBody BootstrapExchangeRequest body,
            @RequestHeader("Origin") String requestOrigin,
            HttpServletRequest request) {
        // The iframe calls its own BFF, so the browser Origin is the application origin.
        // The claimed portal origin is a separate proof bound into the issued opaque code.
        if (!applicationOrigin.equals(requestOrigin) || !portalOrigin.equals(body.origin())) {
            throw new IdentityAccessException("HOST_ORIGIN_FORBIDDEN", "host bootstrap is unavailable");
        }
        HttpSession browserSession = request.getSession(false);
        if (browserSession == null) {
            throw new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required");
        }
        bootstraps.exchange(
                body.bootstrapCode(), body.audience(), body.origin(),
                ContinuationService.digest(BrowserSessionBinding.getOrCreate(browserSession)));
        return Map.of("status", "accepted", "profileVersion", "HIP-1.0.0");
    }

    public record BootstrapExchangeRequest(
            @NotBlank @Pattern(regexp = "hb_[A-Za-z0-9_-]{43,86}") String bootstrapCode,
            @Pattern(regexp = "scholarsense-web") String audience,
            @Pattern(regexp = "https://[A-Za-z0-9.-]+") String origin) {}
}
