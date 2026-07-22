package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityObserver;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityStatePort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditBacklogMeasurement;
import cn.edu.suda.scholarsense.auditoperations.application.FindingIdPort;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditAvailability;
import cn.edu.suda.scholarsense.auditoperations.domain.AvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class JdbcAuditAvailabilityObservationRepository
        implements AuditAvailabilityObserver, AuditAvailabilityStatePort {
    private static final long AVAILABILITY_STREAM_LOCK = 4_701_431_005L;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FindingIdPort identifiers;

    public JdbcAuditAvailabilityObservationRepository(
            JdbcTemplate jdbc, ObjectMapper json, FindingIdPort identifiers) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    @Override
    public void observe(AuditAvailability availability, AuditBacklogMeasurement measurement) {
        jdbc.update("""
                insert into audit_operations.ao_availability_observation (
                  observation_id, state, policy_version, reason_codes, unconfirmed_count,
                  oldest_unconfirmed_age_seconds, chain_healthy, measured_at, fresh_until, trace_id,
                  recovery_healthy_count)
                values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?)
                """,
                identifiers.newId(availability.observedAt()),
                availability.state().name().toLowerCase(),
                availability.policyVersion(), json(availability.reasonCodes()),
                measurement.unconfirmedCount(), measurement.oldestUnconfirmedAgeSeconds(),
                measurement.chainHealthy(), Timestamp.from(availability.observedAt()),
                Timestamp.from(availability.freshUntil()), availability.traceId(),
                availability.recoveryHealthyCount());
    }

    @Override
    public Optional<AuditAvailabilityState> latest() {
        // CurrentAuditAvailabilityService holds a REQUIRED transaction across latest/evaluate/observe.
        // This transaction lock linearizes that state stream across worker JVMs.
        jdbc.execute("select pg_advisory_xact_lock(" + AVAILABILITY_STREAM_LOCK + ")");
        return jdbc.query("""
                select state, reason_codes::text, recovery_healthy_count
                from audit_operations.ao_availability_observation
                order by measured_at desc, observation_id desc limit 1
                """, (result, ignored) -> new AuditAvailabilityState(
                        AvailabilityState.valueOf(result.getString("state").toUpperCase()),
                        reasons(result.getString("reason_codes")),
                        result.getInt("recovery_healthy_count")))
                .stream().findFirst();
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_JSON_INVALID", invalid);
        }
    }

    private EnumSet<FindingCode> reasons(String value) {
        try {
            var reasons = EnumSet.noneOf(FindingCode.class);
            json.readTree(value).forEach(node -> reasons.add(FindingCode.valueOf(node.asText())));
            return reasons;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("AUDIT_AVAILABILITY_JSON_INVALID", invalid);
        }
    }
}
