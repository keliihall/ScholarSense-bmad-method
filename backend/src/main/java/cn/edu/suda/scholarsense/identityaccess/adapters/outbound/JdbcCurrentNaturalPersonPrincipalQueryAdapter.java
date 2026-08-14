package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalQuery;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the explicit account-to-person authority projection through a closed function. */
public final class JdbcCurrentNaturalPersonPrincipalQueryAdapter
        implements CurrentNaturalPersonPrincipalQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcCurrentNaturalPersonPrincipalQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public CurrentNaturalPersonPrincipalResult resolve(CurrentNaturalPersonPrincipalQuery query) {
        try {
            List<Row> rows = jdbc.query("""
                    select * from identity_access.ia_resolve_current_natural_person_principal(?)
                    """, (result, ignored) -> new Row(
                            result.getString("natural_person_principal_digest"),
                            result.getLong("binding_version")), query.accountId());
            if (rows.size() != 1) return unavailable(query, "PERSON_BINDING_INCOMPLETE");
            Row row = rows.getFirst();
            String bindingSetDigest = digest(String.join("\n",
                    query.accountId().toString(), row.principalDigest(),
                    Long.toString(row.bindingVersion())));
            return CurrentNaturalPersonPrincipalResult.available(
                    row.principalDigest(), row.bindingVersion(),
                    bindingSetDigest, query.traceId());
        } catch (RuntimeException failure) {
            return unavailable(query, "PERSON_BINDING_PROVIDER_UNAVAILABLE");
        }
    }

    private static CurrentNaturalPersonPrincipalResult unavailable(
            CurrentNaturalPersonPrincipalQuery query, String reason) {
        return CurrentNaturalPersonPrincipalResult.unavailable(reason, query.traceId());
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private record Row(String principalDigest, long bindingVersion) {}
}
