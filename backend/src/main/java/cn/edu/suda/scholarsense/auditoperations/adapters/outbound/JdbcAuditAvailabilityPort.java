package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityResult;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only online gate. Missing, stale or unreadable observations are returned as unavailable. */
public final class JdbcAuditAvailabilityPort implements AuditAvailabilityPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditClock clock;

    public JdbcAuditAvailabilityPort(JdbcTemplate jdbc, ObjectMapper json, AuditClock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public AuditAvailabilityResult current(String traceId) {
        try {
            return jdbc.query("""
                    select state, policy_version, reason_codes::text, measured_at, fresh_until
                    from audit_operations.ao_availability_observation
                    order by measured_at desc, observation_id desc limit 1
                    """, (result, ignored) -> new AuditAvailabilityResult(
                            AuditAvailabilityState.valueOf(result.getString("state").toUpperCase()),
                            result.getString("policy_version"),
                            reasons(result.getString("reason_codes")),
                            result.getTimestamp("measured_at").toInstant(),
                            result.getTimestamp("fresh_until").toInstant(), traceId))
                    .stream().findFirst().orElseGet(() -> unavailable(traceId));
        } catch (RuntimeException failure) {
            return unavailable(traceId);
        }
    }

    private Set<String> reasons(String encoded) {
        try {
            JsonNode node = json.readTree(encoded);
            Set<String> values = new HashSet<>();
            node.forEach(value -> values.add(value.asText()));
            return Set.copyOf(values);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_JSON_INVALID", invalid);
        }
    }

    private AuditAvailabilityResult unavailable(String traceId) {
        java.time.Instant now = clock.now();
        return new AuditAvailabilityResult(
                AuditAvailabilityState.UNAVAILABLE,
                "AUDIT-INGESTION-POLICY-1.0.0",
                Set.of("AUDIT_AVAILABILITY_UNAVAILABLE"), now, now, traceId);
    }
}
