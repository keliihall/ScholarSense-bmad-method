package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthorizationPolicyAvailabilityPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthorizationPolicySnapshot;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads and preservation-validates the policy manifest plus the complete role
 * mapping. Missing or damaged resources become an unavailable snapshot.
 */
public final class ClasspathIdentityAuthorizationPolicyAdapter
        implements IdentityAuthorizationPolicyAvailabilityPort {
    private static final String PATH =
            "/identity-authority-runtime/identity-authorization-policy-1-0-0.properties";
    private static final Set<String> KEYS = Set.of(
            "schemaVersion",
            "identitySessionPolicyVersion",
            "roleFieldPolicyVersion",
            "roleMappingVersion",
            "roleMappingDigest",
            "roleMappingResource");
    private final IdentityAuthorizationPolicySnapshot snapshot;

    public ClasspathIdentityAuthorizationPolicyAdapter(ObjectMapper json) {
        this(json, ClasspathIdentityAuthorizationPolicyAdapter.class::getResourceAsStream);
    }

    ClasspathIdentityAuthorizationPolicyAdapter(
            ObjectMapper json, Function<String, InputStream> resources) {
        this.snapshot = load(json, resources);
    }

    @Override
    public IdentityAuthorizationPolicySnapshot current() {
        return snapshot;
    }

    private static IdentityAuthorizationPolicySnapshot load(
            ObjectMapper json, Function<String, InputStream> resources) {
        try (InputStream stream = resources.apply(PATH)) {
            if (stream == null) {
                return IdentityAuthorizationPolicySnapshot.unavailable();
            }
            Properties values = new Properties();
            values.load(stream);
            if (!values.stringPropertyNames().equals(KEYS)
                    || !"IDENTITY-AUTHORIZATION-POLICY-1.0.0".equals(
                            values.getProperty("schemaVersion"))
                    || !"ISP-1.0.0".equals(
                            values.getProperty("identitySessionPolicyVersion"))
                    || !"RFP-1.0.0".equals(
                            values.getProperty("roleFieldPolicyVersion"))
                    || !"IDENTITY-ROLE-MAPPING-1.0.0".equals(
                            values.getProperty("roleMappingVersion"))
                    || !values.getProperty("roleMappingDigest", "")
                            .matches("sha256:[0-9a-f]{64}")
                    || !"identity-role-mapping-1-0-0".equals(
                            values.getProperty("roleMappingResource"))) {
                return IdentityAuthorizationPolicySnapshot.unavailable();
            }
            String digest = values.getProperty("roleMappingDigest");
            IdentityAuthorityRuntimeProfile validationProfile =
                    new IdentityAuthorityRuntimeProfile(
                            "IDENTITY-AUTHORITY-PROFILE-1.0.0",
                            "SRC-P0-RESPONSIBILITY-001",
                            "identity-authority",
                            "sandbox-0",
                            "identity-org",
                            URI.create(
                                    "https://test.identity-authority.sandbox.invalid/api/v1/incremental"),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(20),
                            "account://test/identity-sync-worker",
                            "secret://test/identity-authority-signature",
                            "config://test/identity-authority-inbox",
                            "config://test/"
                                    + values.getProperty("roleMappingResource"),
                            values.getProperty("roleMappingVersion"),
                            digest,
                            Duration.ofSeconds(30),
                            5,
                            false);
            ApprovedIdentityRoleMapping.from(validationProfile, json, resources);
            return new IdentityAuthorizationPolicySnapshot(
                    true,
                    values.getProperty("identitySessionPolicyVersion"),
                    values.getProperty("roleFieldPolicyVersion"),
                    values.getProperty("roleMappingVersion"),
                    digest);
        } catch (IOException | RuntimeException unavailable) {
            return IdentityAuthorizationPolicySnapshot.unavailable();
        }
    }
}
