package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.net.URI;

public final class RequestOriginPolicy {
    private final URI allowedOrigin;

    public RequestOriginPolicy(String allowedOrigin) {
        this.allowedOrigin = originOf(allowedOrigin);
        if (this.allowedOrigin == null) {
            throw new IllegalArgumentException("IDENTITY_ALLOWED_ORIGIN_INVALID");
        }
    }

    public void requireAllowed(String origin, String referer) {
        URI suppliedOrigin = originOf(origin);
        if (suppliedOrigin != null && suppliedOrigin.equals(allowedOrigin)) {
            return;
        }
        URI suppliedReferer = originOf(referer);
        if (origin == null && suppliedReferer != null && suppliedReferer.equals(allowedOrigin)) {
            return;
        }
        throw new IdentityAccessException("IDENTITY_CSRF_INVALID", "request origin validation failed");
    }

    private static URI originOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            int port = uri.getPort();
            return new URI(uri.getScheme(), null, uri.getHost(), port, null, null, null);
        } catch (Exception invalid) {
            return null;
        }
    }
}
