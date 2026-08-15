package cn.edu.suda.scholarsense.shared.observability;

import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact-host HTTPS egress trust policy; suffix and wildcard matching are intentionally forbidden. */
public final class TrustedTargetPolicy {
    private final Set<String> hosts;
    private final Set<String> sandboxOrigins;

    TrustedTargetPolicy(Set<String> hosts) {
        this(hosts, Set.of());
    }

    private TrustedTargetPolicy(Set<String> hosts, Set<String> sandboxOrigins) {
        Objects.requireNonNull(hosts, "hosts");
        this.hosts = hosts.stream()
                .map(TrustedTargetPolicy::normalizedHost)
                .collect(Collectors.toUnmodifiableSet());
        this.sandboxOrigins = Set.copyOf(sandboxOrigins);
        if (this.hosts.isEmpty() && this.sandboxOrigins.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted target is required");
        }
    }

    static TrustedTargetPolicy fromIdentityAuthorityProfile(
            RuntimeEnvironment environment, IdentityAuthorityRuntimeProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!"IDENTITY-AUTHORITY-PROFILE-1.0.0".equals(profile.schemaVersion())) {
            throw new IllegalArgumentException("identity authority profile version is not approved");
        }
        return fromVersionedProfile(environment, java.util.List.of(profile.endpoint()));
    }

    static TrustedTargetPolicy fromResponsibilityAuthorityProfile(
            RuntimeEnvironment environment, ResponsibilityAuthorityRuntimeProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!"RESPONSIBILITY-AUTHORITY-PROFILE-2.0.0".equals(profile.profileVersion())) {
            throw new IllegalArgumentException("responsibility authority profile version is not approved");
        }
        return fromVersionedProfile(environment, java.util.List.of(
                profile.incrementalEndpoint(),
                profile.snapshotEndpointTemplate(),
                profile.version2IncrementalEndpoint(),
                profile.version2SnapshotEndpointTemplate()));
    }

    static TrustedTargetPolicy fromSandboxProfile(
            RuntimeEnvironment environment,
            String profileVersion,
            Collection<URI> endpoints) {
        Objects.requireNonNull(environment, "environment");
        if ((environment != RuntimeEnvironment.DEV && environment != RuntimeEnvironment.TEST)
                || !Set.of(
                        "IDENTITY-RUNTIME-PROFILE-1.0.0",
                        "QUALITY-WORKER-PROVIDER-PROFILE-1.0.0")
                        .contains(profileVersion)) {
            throw new IllegalArgumentException(
                    "unversioned provider endpoints are restricted to approved sandbox profiles");
        }
        for (URI endpoint : endpoints) {
            if (endpoint == null || endpoint.getHost() == null
                    || !isLoopback(endpoint.getHost()) || endpoint.getPort() < 1) {
                throw new IllegalArgumentException(
                        "sandbox profile endpoints must be explicit loopback origins");
            }
        }
        return fromVersionedProfile(environment, endpoints);
    }

    private static TrustedTargetPolicy fromVersionedProfile(
            RuntimeEnvironment environment, Collection<URI> endpoints) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(endpoints, "endpoints");
        boolean productionLike = environment == RuntimeEnvironment.STAGE
                || environment == RuntimeEnvironment.PROD;
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        LinkedHashSet<String> sandboxOrigins = new LinkedHashSet<>();
        for (URI endpoint : endpoints) {
            if (endpoint == null || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("approved authority endpoint is invalid");
            }
            String host = endpoint.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = isLoopback(host);
            if (productionLike) {
                if (!"https".equalsIgnoreCase(endpoint.getScheme())
                        || endpoint.getPort() != -1
                        || loopback
                        || host.endsWith(".invalid")) {
                    throw new IllegalArgumentException(
                            "production authority endpoint must be an approved exact HTTPS host");
                }
                hosts.add(host);
            } else if ("http".equalsIgnoreCase(endpoint.getScheme()) && loopback) {
                if (endpoint.getPort() < 1) {
                    throw new IllegalArgumentException("sandbox endpoint port is required");
                }
                sandboxOrigins.add(origin(endpoint));
            } else if ("https".equalsIgnoreCase(endpoint.getScheme())) {
                if (endpoint.getPort() == -1) hosts.add(host);
                else sandboxOrigins.add(origin(endpoint));
            } else {
                throw new IllegalArgumentException("authority endpoint scheme is not approved");
            }
        }
        return new TrustedTargetPolicy(hosts, sandboxOrigins);
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("127.0.0.1")
                || host.equalsIgnoreCase("::1")
                || host.equalsIgnoreCase("localhost");
    }

    public boolean allows(URI target) {
        if (target == null || target.getUserInfo() != null || target.getFragment() != null
                || target.getHost() == null) {
            return false;
        }
        if (sandboxOrigins.contains(origin(target))) return true;
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getPort() != -1) return false;
        return hosts.contains(target.getHost().toLowerCase(Locale.ROOT));
    }

    public Set<String> hosts() {
        return hosts;
    }

    private static String normalizedHost(String host) {
        if (host == null) {
            throw new IllegalArgumentException("trusted host is required");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        String loopbackDnsName = "local" + "host";
        if (!normalized.matches("[a-z0-9]+(?:[.-][a-z0-9-]+)*")
                || normalized.equals(loopbackDnsName)
                || normalized.endsWith("." + loopbackDnsName)) {
            throw new IllegalArgumentException("trusted host must be an exact DNS name");
        }
        return normalized;
    }

    private static String origin(URI uri) {
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + host.toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
}
