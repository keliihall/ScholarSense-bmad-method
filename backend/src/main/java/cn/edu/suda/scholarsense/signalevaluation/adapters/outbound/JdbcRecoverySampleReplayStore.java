package cn.edu.suda.scholarsense.signalevaluation.adapters.outbound;

import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleReplayEntry;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleReplayStorePort;
import java.util.List;
import java.util.Optional;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable replay winner for provider-version and canonical request digest. */
public final class JdbcRecoverySampleReplayStore implements RecoverySampleReplayStorePort {
    private final JdbcTemplate jdbc;
    private final RecoverySamplePersistenceJsonCodec codec;

    public JdbcRecoverySampleReplayStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.codec = new RecoverySamplePersistenceJsonCodec(json);
    }

    @Override
    public Optional<RecoverySampleReplayEntry> find(String providerVersion, String requestDigest) {
        List<String> values = jdbc.query("""
                select signal_evaluation.se_find_recovery_sample_replay(?,?)::text
                """, (row, ignored) -> row.getString(1), providerVersion, requestDigest);
        return values.isEmpty() || values.getFirst() == null
                ? Optional.empty() : Optional.of(read(providerVersion, requestDigest, values.getFirst()));
    }

    @Override
    public RecoverySampleReplayEntry insertIfAbsent(RecoverySampleReplayEntry requested) {
        String encoded = jdbc.queryForObject("""
                select signal_evaluation.se_insert_recovery_sample_replay(
                    ?,?,?,?::jsonb,?)::text
                """, String.class, requested.providerVersion(), requested.requestDigest(),
                requested.canonicalBodyDigest(), codec.write(requested.response()),
                Timestamp.from(completedAt(requested)));
        return read(requested.providerVersion(), requested.requestDigest(),
                java.util.Objects.requireNonNull(encoded));
    }

    private RecoverySampleReplayEntry read(
            String providerVersion, String requestDigest, String encoded) {
        var root = codec.tree(encoded);
        return new RecoverySampleReplayEntry(
                providerVersion, requestDigest,
                RecoverySamplePersistenceJsonCodec.text(root, "canonicalBodyDigest"),
                codec.read(codec.string(root.required("response"))));
    }

    private static java.time.Instant completedAt(RecoverySampleReplayEntry value) {
        return value.response().result() == null
                ? java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                : value.response().result().completedAt();
    }
}
