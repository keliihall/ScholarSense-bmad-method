package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityReplayPort;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists gap requests; a later continuous batch marks the exact interval covered. */
public final class JdbcIdentityReplayAdapter implements IdentityReplayPort {
    private final JdbcTemplate jdbc;
    private final TrustedTimeSource time;

    public JdbcIdentityReplayAdapter(JdbcTemplate jdbc, TrustedTimeSource time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    @Override
    public void request(
            CheckpointKey key, long fromInclusive, long toInclusive, String traceId) {
        var now = time.now().instant();
        jdbc.update("""
                insert into identity_access.ia_identity_replay_request (
                  request_id, source_id, feed_id, partition_id, consumer_projection,
                  requested_from, requested_to, status, trace_id, requested_at,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, 'requested', ?, ?, ?, ?)
                on conflict (
                  source_id, feed_id, partition_id, consumer_projection,
                  requested_from, requested_to, trace_id) do nothing
                """,
                UUID.fromString(UuidV7.generate(now)),
                key.sourceId(), key.feedId(), key.partitionId(),
                key.consumerProjection(), fromInclusive, toInclusive, traceId,
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now.plus(Duration.ofDays(90))));
    }

    @Override
    public Optional<cn.edu.suda.scholarsense.identityaccess.application.IdentityReplayRange>
            nextRequested(CheckpointKey key) {
        List<cn.edu.suda.scholarsense.identityaccess.application.IdentityReplayRange> ranges =
                jdbc.query("""
                        select requested_from, requested_to
                          from identity_access.ia_identity_replay_request
                         where source_id=? and feed_id=? and partition_id=?
                           and consumer_projection=? and status='requested'
                         order by requested_from, requested_at, request_id
                         limit 1
                        """,
                        (rs, row) ->
                                new cn.edu.suda.scholarsense.identityaccess.application
                                        .IdentityReplayRange(
                                        rs.getLong("requested_from"),
                                        rs.getLong("requested_to")),
                        key.sourceId(), key.feedId(), key.partitionId(),
                        key.consumerProjection());
        return ranges.stream().findFirst();
    }
}
