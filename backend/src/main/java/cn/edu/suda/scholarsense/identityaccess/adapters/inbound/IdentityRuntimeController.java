package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity-runtime")
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
public final class IdentityRuntimeController {
    private final String portalOrigin;

    public IdentityRuntimeController(@Value("${scholarsense.identity.portal-origin}") String portalOrigin) {
        URI parsed = URI.create(portalOrigin);
        if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null
                || parsed.getUserInfo() != null || !parsed.toString().equals(portalOrigin)
                || parsed.getPath() != null && !parsed.getPath().isEmpty()) {
            throw new IllegalArgumentException("HOST_ORIGIN_INVALID");
        }
        this.portalOrigin = portalOrigin;
    }

    @GetMapping
    public Map<String, String> runtime() {
        return Map.of("schemaVersion", "HIP-1.0.0", "portalOrigin", portalOrigin);
    }
}
