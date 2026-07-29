package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncObservabilityPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncObservation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MicrometerIdentitySyncObservabilityAdapter
        implements IdentitySyncObservabilityPort {
    private static final String BACKLOG_SQL = """
            select count(*)
              from identity_access.ia_identity_sync_job
             where status in ('queued', 'running')
            """;
    private static final String LAST_SUCCESS_SQL = """
            select coalesce(extract(epoch from max(last_successful_at)), 0)
              from identity_access.ia_identity_sync_checkpoint
            """;
    private static final String WATERMARK_SQL = """
            select coalesce(max(source_watermark), 0)
              from identity_access.ia_identity_sync_checkpoint
            """;
    private static final String WATERMARK_LAG_SQL = """
            select greatest(
              coalesce((
                select max(requested_to)
                  from identity_access.ia_identity_replay_request
                 where status='requested'), 0)
              - coalesce((
                select max(source_watermark)
                  from identity_access.ia_identity_sync_checkpoint), 0),
              0)
            """;
    private static final String QUARANTINE_SQL = """
            select count(*)
              from identity_access.ia_identity_rejected_record
            """;
    private static final String SLO_NUMERATOR_SQL = """
            select count(*)
              from identity_access.ia_identity_slo_evidence
             where authorization_effective_at >= current_timestamp - interval '30 days'
               and within_fifteen_minutes
            """;
    private static final String SLO_DENOMINATOR_SQL = """
            select (
              (select count(*)
                 from identity_access.ia_identity_slo_evidence
                where authorization_effective_at >=
                      current_timestamp - interval '30 days')
              +
              (select count(*)
                 from identity_access.ia_identity_slo_compensation
                where requested_at >= current_timestamp - interval '30 days'
                  and status='pending')
            )
            """;
    private static final String SLO_RATE_SQL = """
            select case when denominator.total=0 then 0.0
                        else numerator.total::double precision / denominator.total end
              from (
                select count(*) as total
                  from identity_access.ia_identity_slo_evidence
                 where authorization_effective_at >=
                       current_timestamp - interval '30 days'
                   and within_fifteen_minutes
              ) numerator
              cross join (
                select (
                  (select count(*)
                     from identity_access.ia_identity_slo_evidence
                    where authorization_effective_at >=
                          current_timestamp - interval '30 days')
                  +
                  (select count(*)
                     from identity_access.ia_identity_slo_compensation
                    where requested_at >= current_timestamp - interval '30 days'
                      and status='pending')
                ) as total
              ) denominator
            """;

    private final MeterRegistry registry;

    public MicrometerIdentitySyncObservabilityAdapter(MeterRegistry registry) {
        this.registry = registry;
    }

    public MicrometerIdentitySyncObservabilityAdapter(
            MeterRegistry registry, JdbcTemplate jdbc) {
        this(registry);
        registry.gauge(
                "identity_sync_job_backlog", jdbc,
                source -> longValue(source, BACKLOG_SQL));
        registry.gauge(
                "identity_sync_last_success_epoch_seconds", jdbc,
                source -> doubleValue(source, LAST_SUCCESS_SQL));
        registry.gauge(
                "identity_sync_checkpoint_watermark", jdbc,
                source -> longValue(source, WATERMARK_SQL));
        registry.gauge(
                "identity_sync_watermark_lag", jdbc,
                source -> longValue(source, WATERMARK_LAG_SQL));
        registry.gauge(
                "identity_sync_quarantine_count", jdbc,
                source -> longValue(source, QUARANTINE_SQL));
        registry.gauge(
                "identity_sync_slo_30d_numerator", jdbc,
                source -> longValue(source, SLO_NUMERATOR_SQL));
        registry.gauge(
                "identity_sync_slo_30d_denominator", jdbc,
                source -> longValue(source, SLO_DENOMINATOR_SQL));
        registry.gauge(
                "identity_sync_slo_30d_rate", jdbc,
                source -> doubleValue(source, SLO_RATE_SQL));
    }

    @Override
    public void record(IdentitySyncObservation observation) {
        List<Tag> tags = new ArrayList<>();
        observation.labels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> tags.add(Tag.of(entry.getKey(), entry.getValue())));
        registry.counter(observation.metric(), tags).increment(observation.value());
    }

    private static double longValue(JdbcTemplate jdbc, String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    private static double doubleValue(JdbcTemplate jdbc, String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? Double.NaN : value;
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
