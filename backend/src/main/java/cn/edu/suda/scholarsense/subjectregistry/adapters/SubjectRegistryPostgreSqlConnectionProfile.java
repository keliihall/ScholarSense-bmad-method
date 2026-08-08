package cn.edu.suda.scholarsense.subjectregistry.adapters;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validated non-secret connection metadata for a subject-registry workload. */
public record SubjectRegistryPostgreSqlConnectionProfile(
        String environment, String jdbcUrl, String expectedWorkloadIdentity) {

    static SubjectRegistryPostgreSqlConnectionProfile validate(
            String environment,
            String jdbcUrl,
            String connectedIdentity,
            String expectedWorkloadIdentity) {
        if (!Set.of("dev", "test", "stage", "prod").contains(environment)
                || jdbcUrl == null
                || !jdbcUrl.startsWith("jdbc:postgresql://")
                || jdbcUrl.substring("jdbc:postgresql://".length())
                        .split("/", 2)[0].contains("@")) {
            throw invalid();
        }
        if (connectedIdentity == null
                || !connectedIdentity.equals(expectedWorkloadIdentity)
                || !connectedIdentity.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException(
                    "SUBJECT_REGISTRY_DATABASE_IDENTITY_MISMATCH");
        }
        Map<String, String> query = query(jdbcUrl);
        if (query.containsKey("user") || query.containsKey("password")) throw invalid();
        if ("prod".equals(environment)
                && (!"verify-full".equals(query.get("sslmode"))
                    || !"require".equals(query.get("channelbinding")))) {
            throw new IllegalArgumentException(
                    "SUBJECT_REGISTRY_DATABASE_CHANNEL_BINDING_REQUIRED");
        }
        return new SubjectRegistryPostgreSqlConnectionProfile(
                environment, jdbcUrl, expectedWorkloadIdentity);
    }

    private static Map<String, String> query(String jdbcUrl) {
        int marker = jdbcUrl.indexOf('?');
        if (marker < 0 || marker == jdbcUrl.length() - 1) return Map.of();
        try {
            return Arrays.stream(jdbcUrl.substring(marker + 1).split("&"))
                    .map(pair -> pair.split("=", 2))
                    .filter(pair -> pair.length == 2)
                    .collect(Collectors.toUnmodifiableMap(
                            pair -> pair[0].toLowerCase(Locale.ROOT),
                            pair -> pair[1].toLowerCase(Locale.ROOT),
                            (left, right) -> { throw invalid(); }));
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SUBJECT_REGISTRY_DATABASE_PROFILE_INVALID");
    }
}
