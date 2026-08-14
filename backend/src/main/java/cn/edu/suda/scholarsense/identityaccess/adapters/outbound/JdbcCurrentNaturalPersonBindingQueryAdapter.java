package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonBinding;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonBindingQuery;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonBindingQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonBindingResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Resolves current accountable owners to distinct opaque natural-person principals. */
public final class JdbcCurrentNaturalPersonBindingQueryAdapter
        implements CurrentNaturalPersonBindingQueryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcCurrentNaturalPersonBindingQueryAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public CurrentNaturalPersonBindingResult resolve(CurrentNaturalPersonBindingQuery value) {
        try {
            String owners = json.writeValueAsString(value.businessOwnerKeyDigests());
            List<CurrentNaturalPersonBinding> bindings = jdbc.query("""
                    select * from identity_access.ia_resolve_current_natural_person_bindings(
                        ?::jsonb,?)
                    """, (row, ignored) -> new CurrentNaturalPersonBinding(
                            row.getString("business_owner_key_digest"),
                            row.getString("natural_person_principal_digest"),
                            row.getLong("binding_version"),
                            row.getTimestamp("effective_from").toInstant(),
                            row.getTimestamp("effective_to") == null ? null
                                    : row.getTimestamp("effective_to").toInstant()),
                    owners, java.sql.Timestamp.from(value.trustedAt()));
            if (bindings.size() != value.businessOwnerKeyDigests().size()) {
                return unavailable(value.traceId(), "PERSON_BINDING_INCOMPLETE");
            }
            String canonical = bindings.stream()
                    .map(item -> item.businessOwnerKeyDigest() + '\u001f'
                            + item.naturalPersonPrincipalDigest() + '\u001f'
                            + item.bindingVersion())
                    .sorted().collect(java.util.stream.Collectors.joining("\u001e"));
            return new CurrentNaturalPersonBindingResult(
                    CurrentNaturalPersonBindingResult.Availability.AVAILABLE,
                    bindings, digest(canonical), null, value.traceId());
        } catch (RuntimeException failure) {
            return unavailable(value.traceId(), "PERSON_BINDING_PROVIDER_UNAVAILABLE");
        }
    }

    private static CurrentNaturalPersonBindingResult unavailable(String traceId, String reason) {
        return new CurrentNaturalPersonBindingResult(
                CurrentNaturalPersonBindingResult.Availability.UNAVAILABLE,
                List.of(), null, reason, traceId);
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
