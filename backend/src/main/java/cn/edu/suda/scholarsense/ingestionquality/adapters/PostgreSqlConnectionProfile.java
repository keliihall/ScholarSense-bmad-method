package cn.edu.suda.scholarsense.ingestionquality.adapters;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Validated non-secret connection metadata; credentials stay in the driver credential provider. */
public record PostgreSqlConnectionProfile(
        String environment,
        String jdbcUrl,
        String expectedWorkloadIdentity) {

    public static PostgreSqlConnectionProfile validate(
            String environment,
            String jdbcUrl,
            String connectedIdentity,
            String expectedWorkloadIdentity) {
        if (!java.util.Set.of("dev", "test", "stage", "prod").contains(environment)) {
            throw invalid();
        }
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")
                || jdbcUrl.substring("jdbc:postgresql://".length()).split("/", 2)[0].contains("@")) {
            throw invalid();
        }
        if (connectedIdentity == null || !connectedIdentity.equals(expectedWorkloadIdentity)
                || !connectedIdentity.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_DATABASE_IDENTITY_MISMATCH");
        }
        Map<String, String> query = query(jdbcUrl);
        if ("prod".equals(environment)
                && (!"verify-full".equals(query.get("sslmode"))
                        || !"require".equals(query.get("channelbinding")))) {
            throw new IllegalArgumentException("INGESTION_QUALITY_DATABASE_CHANNEL_BINDING_REQUIRED");
        }
        return new PostgreSqlConnectionProfile(environment, jdbcUrl, expectedWorkloadIdentity);
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
        return new IllegalArgumentException("INGESTION_QUALITY_DATABASE_PROFILE_INVALID");
    }
}
