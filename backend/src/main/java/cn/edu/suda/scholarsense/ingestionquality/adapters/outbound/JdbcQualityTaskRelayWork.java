package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskRelayClaim;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskRelayWorkPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed-function JDBC adapter for the producer-owned quality-task delivery sidecar. */
public final class JdbcQualityTaskRelayWork implements QualityTaskRelayWorkPort {
    private static final String EVENT_TYPE =
            "scholarsense.ingestion-quality.quality-recovery-task.changed.v1";
    private static final String ROUTE_LOCK_PREFIX = "quality-task-route:";
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper json;

    public JdbcQualityTaskRelayWork(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource());
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public QualityTaskRelayClaim claimNext() {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_claim_next_quality_task_outbox()::text
                """, String.class);
        if (value == null) return null;
        try {
            JsonNode claim = json.readTree(value);
            UUID eventId = UUID.fromString(requiredText(claim, "eventId"));
            UUID taskId = UUID.fromString(requiredText(claim, "taskId"));
            long routeSequence = requiredPositive(claim, "routeSequence");
            long attempt = requiredPositive(claim, "attempt");
            long leaseGeneration = requiredPositive(claim, "leaseGeneration");
            String payload = requiredText(claim, "payload");
            String payloadDigest = requiredText(claim, "payloadDigest");
            verifyPayload(payload, payloadDigest, eventId, taskId, routeSequence);
            return new QualityTaskRelayClaim(
                    eventId, taskId, routeSequence, payload, payloadDigest,
                    attempt, leaseGeneration);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_TASK_RELAY_PAYLOAD_INVALID", invalid);
        }
    }

    @Override
    public SendPermit acquireSendPermit(QualityTaskRelayClaim claim) {
        Objects.requireNonNull(claim);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean locked = false;
        try {
            execute(connection, """
                    select pg_catalog.pg_advisory_lock(
                        pg_catalog.hashtextextended(?,0))
                    """, routeLockKey(claim.taskId()));
            locked = true;
            boolean authorized = queryBoolean(connection, """
                    select ingestion_quality.iq_authorize_quality_task_send(?,?,?)
                    """, claim.eventId(), claim.leaseGeneration(), claim.routeSequence());
            return new JdbcSendPermit(connection, claim, authorized);
        } catch (RuntimeException failure) {
            try {
                release(connection, claim.taskId(), locked);
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    private final class JdbcSendPermit implements SendPermit {
        private final Connection connection;
        private final QualityTaskRelayClaim claim;
        private final boolean authorized;
        private boolean closed;

        private JdbcSendPermit(
                Connection connection, QualityTaskRelayClaim claim, boolean authorized) {
            this.connection = connection;
            this.claim = claim;
            this.authorized = authorized;
        }

        @Override
        public boolean authorized() {
            return authorized;
        }

        @Override
        public boolean confirm(String receiptId) {
            return finalizeWith(
                    "select ingestion_quality.iq_complete_quality_task_delivery(?,?,?)",
                    receiptId);
        }

        @Override
        public boolean retry(String errorCode) {
            return finalizeWith(
                    "select ingestion_quality.iq_mark_quality_task_delivery_retry(?,?,?)",
                    errorCode);
        }

        @Override
        public boolean fail(String errorCode) {
            return finalizeWith(
                    "select ingestion_quality.iq_fail_quality_task_delivery(?,?,?)",
                    errorCode);
        }

        private boolean finalizeWith(String sql, String value) {
            if (closed || !authorized) {
                throw new IllegalStateException("INGESTION_QUALITY_TASK_SEND_PERMIT_INVALID");
            }
            return queryBoolean(
                    connection, sql, claim.eventId(), claim.leaseGeneration(), value);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release(connection, claim.taskId(), true);
            }
        }
    }

    private void release(Connection connection, UUID taskId, boolean locked) {
        RuntimeException unlockFailure = null;
        if (locked) {
            try {
                if (!queryBoolean(connection, """
                        select pg_catalog.pg_advisory_unlock(
                            pg_catalog.hashtextextended(?,0))
                        """, routeLockKey(taskId))) {
                    unlockFailure = new IllegalStateException(
                            "INGESTION_QUALITY_TASK_SEND_PERMIT_UNLOCK_FAILED");
                }
            } catch (RuntimeException failure) {
                unlockFailure = failure;
            }
        }
        DataSourceUtils.releaseConnection(connection, dataSource);
        if (unlockFailure != null) throw unlockFailure;
    }

    private static String routeLockKey(UUID taskId) {
        return ROUTE_LOCK_PREFIX + Objects.requireNonNull(taskId);
    }

    private static void execute(Connection connection, String sql, Object... arguments) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            statement.execute();
        } catch (SQLException failure) {
            throw new IllegalStateException("INGESTION_QUALITY_TASK_SEND_PERMIT_FAILED", failure);
        }
    }

    private static boolean queryBoolean(
            Connection connection, String sql, Object... arguments) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1) && !result.wasNull();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("INGESTION_QUALITY_TASK_SEND_PERMIT_FAILED", failure);
        }
    }

    private static void bind(PreparedStatement statement, Object... arguments)
            throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    private void verifyPayload(
            String payload,
            String expectedDigest,
            UUID eventId,
            UUID taskId,
            long routeSequence) {
        if (!digest(payload).equals(expectedDigest)) throw invalid();
        JsonNode event = json.readTree(payload);
        JsonNode data = event.path("data");
        if (!"1.0".equals(event.path("specversion").asText())
                || !eventId.toString().equals(event.path("id").asText())
                || !EVENT_TYPE.equals(event.path("type").asText())
                || !"application/json".equals(event.path("datacontenttype").asText())
                || !eventId.toString().equals(data.path("eventId").asText())
                || !taskId.toString().equals(data.path("taskId").asText())
                || routeSequence != data.path("routeSequence").asLong()
                || !"PIC-1.1.0".equals(data.path("contractVersion").asText())
                || !"none".equals(data.path("runtimeEvidenceClaim").asText())) {
            throw invalid();
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static long requiredPositive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < 1) throw invalid();
        return value.asLong();
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_TASK_RELAY_PAYLOAD_INVALID");
    }
}
