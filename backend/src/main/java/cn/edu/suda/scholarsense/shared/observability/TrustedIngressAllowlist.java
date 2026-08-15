package cn.edu.suda.scholarsense.shared.observability;

import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned exact proxy socket and asserted-identity policy for remote trace context. */
public final class TrustedIngressAllowlist {
    public static final String PROFILE_VERSION = "TRUSTED-INGRESS-1.0.0";
    private final String profileVersion;
    private final Map<String, String> proxyIdentityBySocket;

    public TrustedIngressAllowlist(
            String profileVersion, Map<String, String> proxyIdentityBySocket) {
        if (!PROFILE_VERSION.equals(profileVersion)) {
            throw new IllegalArgumentException("trusted ingress profile version is stale");
        }
        Objects.requireNonNull(proxyIdentityBySocket, "proxyIdentityBySocket");
        LinkedHashMap<String, String> validated = new LinkedHashMap<>();
        proxyIdentityBySocket.forEach((address, identity) -> {
            if (!validAddress(address)
                    || identity == null
                    || !identity.matches("[a-z][a-z0-9-]{2,63}")) {
                throw new IllegalArgumentException("trusted ingress proxy binding is invalid");
            }
            validated.put(address, identity);
        });
        if (validated.isEmpty()) {
            throw new IllegalArgumentException("trusted ingress proxy bindings are required");
        }
        this.profileVersion = profileVersion;
        this.proxyIdentityBySocket = Map.copyOf(validated);
    }

    public static TrustedIngressAllowlist forEnvironment(RuntimeEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        return switch (environment) {
            case DEV -> new TrustedIngressAllowlist(PROFILE_VERSION, Map.of(
                    "127.0.0.1", "portal-proxy-dev-v1",
                    "::1", "portal-proxy-dev-v1"));
            case TEST -> new TrustedIngressAllowlist(PROFILE_VERSION, Map.of(
                    "127.0.0.1", "portal-proxy-test-v1",
                    "::1", "portal-proxy-test-v1"));
            case STAGE -> new TrustedIngressAllowlist(
                    PROFILE_VERSION, Map.of("10.20.0.10", "portal-proxy-stage-v1"));
            case PROD -> new TrustedIngressAllowlist(
                    PROFILE_VERSION, Map.of("10.30.0.10", "portal-proxy-prod-v1"));
        };
    }

    public String profileVersion() {
        return profileVersion;
    }

    public boolean allows(String remoteAddress, String assertedProxyIdentity) {
        if (remoteAddress == null || assertedProxyIdentity == null) return false;
        return assertedProxyIdentity.equals(proxyIdentityBySocket.get(remoteAddress));
    }

    private static boolean validAddress(String value) {
        return value != null && (value.matches(
                "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])"
                        + "(?:\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9]?[0-9])){3}")
                || value.matches("[0-9a-f:]{2,45}"));
    }
}
