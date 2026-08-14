package cn.edu.suda.scholarsense.rulegovernance.adapters.outbound;

import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBinding;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingQuery;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingQueryPort;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingResult;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Rule-governance-owned current business-owner binding projection. */
public final class JdbcRuleVersionBusinessOwnerBindingQueryAdapter
        implements RuleVersionBusinessOwnerBindingQueryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRuleVersionBusinessOwnerBindingQueryAdapter(
            JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public RuleVersionBusinessOwnerBindingResult resolve(
            RuleVersionBusinessOwnerBindingQuery value) {
        try {
            String rules = json.writeValueAsString(value.ruleVersionDigests());
            List<Row> rows = value.bindingSetDigest() == null
                    ? jdbc.query("""
                        select * from rule_governance.rg_read_current_rule_version_business_owners(
                            ?::jsonb,?)
                        """, (row, ignored) -> map(row), rules,
                            java.sql.Timestamp.from(value.trustedAt()))
                    : jdbc.query("""
                        select rule_version_digest,business_owner_key_digest,binding_version,
                               effective_from,effective_to,? as binding_set_digest
                          from rule_governance.rg_resolve_rule_version_business_owners(
                            ?::jsonb,?,?)
                        """, (row, ignored) -> map(row), value.bindingSetDigest(), rules,
                            value.bindingSetDigest(), java.sql.Timestamp.from(value.trustedAt()));
            List<RuleVersionBusinessOwnerBinding> bindings = rows.stream().map(Row::binding).toList();
            String bindingSetDigest = rows.isEmpty() ? null : rows.getFirst().bindingSetDigest();
            if (rows.stream().anyMatch(row ->
                    !java.util.Objects.equals(bindingSetDigest, row.bindingSetDigest()))) {
                return unavailable(value.traceId(), "RULE_OWNER_BINDING_AMBIGUOUS");
            }
            if (bindings.size() != value.ruleVersionDigests().size()) {
                return unavailable(value.traceId(), "RULE_OWNER_BINDING_INCOMPLETE");
            }
            return new RuleVersionBusinessOwnerBindingResult(
                    RuleVersionBusinessOwnerBindingResult.Availability.AVAILABLE,
                    bindings, bindingSetDigest, null, value.traceId());
        } catch (RuntimeException failure) {
            return unavailable(value.traceId(), "RULE_OWNER_PROVIDER_UNAVAILABLE");
        }
    }

    private static Row map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Row(new RuleVersionBusinessOwnerBinding(
                row.getString("rule_version_digest"),
                row.getString("business_owner_key_digest"),
                row.getLong("binding_version"),
                row.getTimestamp("effective_from").toInstant(),
                row.getTimestamp("effective_to") == null ? null
                        : row.getTimestamp("effective_to").toInstant()),
                row.getString("binding_set_digest"));
    }

    private record Row(
            RuleVersionBusinessOwnerBinding binding, String bindingSetDigest) {}

    private static RuleVersionBusinessOwnerBindingResult unavailable(
            String traceId, String reason) {
        return new RuleVersionBusinessOwnerBindingResult(
                RuleVersionBusinessOwnerBindingResult.Availability.UNAVAILABLE,
                List.of(), null, reason, traceId);
    }
}
