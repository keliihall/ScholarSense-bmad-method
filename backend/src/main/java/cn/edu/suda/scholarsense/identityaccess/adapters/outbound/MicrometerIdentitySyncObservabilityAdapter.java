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
    private static final String RESPONSIBILITY_LAST_SUCCESS_SQL = """
            select coalesce(extract(epoch from max(last_successful_at)), 0)
              from identity_access.ia_identity_sync_checkpoint
             where consumer_projection='responsibility'
            """;
    private static final String RESPONSIBILITY_WATERMARK_LAG_SQL = """
            select greatest(
              coalesce((
                select max(requested_to)
                  from identity_access.ia_identity_replay_request
                 where consumer_projection='responsibility'
                   and status='requested'), 0)
              - coalesce((
                select max(source_watermark)
                  from identity_access.ia_identity_sync_checkpoint
                 where consumer_projection='responsibility'), 0),
              0)
            """;
    private static final String RESPONSIBILITY_SLO_NUMERATOR_SQL = """
            select count(*)
              from identity_access.ia_responsibility_slo_evidence
             where authorization_effective_at >=
                   current_timestamp - interval '30 days'
               and within_fifteen_minutes
            """;
    private static final String RESPONSIBILITY_SLO_DENOMINATOR_SQL = """
            select (
              (select count(*)
                 from identity_access.ia_responsibility_slo_evidence
                where authorization_effective_at >=
                      current_timestamp - interval '30 days')
              +
              (select count(*)
                 from identity_access.ia_responsibility_slo_compensation
                      compensation
                where requested_at >= current_timestamp - interval '30 days'
                  and status='pending'
                  and not exists (
                    select 1
                      from identity_access.ia_responsibility_slo_evidence
                           evidence
                     where evidence.evidence_id=
                           compensation.evidence_id))
            )
            """;
    private static final String RESPONSIBILITY_SLO_RATE_SQL = """
            select case when denominator.total=0 then 0.0
                        else numerator.total::double precision /
                             denominator.total end
              from (
                select count(*) as total
                  from identity_access.ia_responsibility_slo_evidence
                 where authorization_effective_at >=
                       current_timestamp - interval '30 days'
                   and within_fifteen_minutes
              ) numerator
              cross join (
                select (
                  (select count(*)
                     from identity_access.ia_responsibility_slo_evidence
                    where authorization_effective_at >=
                          current_timestamp - interval '30 days')
                  +
                  (select count(*)
                     from identity_access.ia_responsibility_slo_compensation
                          compensation
                    where requested_at >=
                          current_timestamp - interval '30 days'
                      and status='pending'
                      and not exists (
                        select 1
                          from identity_access.ia_responsibility_slo_evidence
                               evidence
                         where evidence.evidence_id=
                               compensation.evidence_id))
                ) as total
              ) denominator
            """;
    private static final String RESPONSIBILITY_DAILY_RUN_AGE_SQL = """
            select coalesce(
              extract(epoch from (
                current_timestamp - max(completed_at))), 0)
              from identity_access.ia_responsibility_reconciliation_run
            """;
    private static final String RESPONSIBILITY_DAILY_QUALITY_SQL = """
            select coalesce((
              select case when reconciliation_outcome='matched'
                                and match_rate>=0.999000
                                and active_unmapped_count=0
                          then 1 else 0 end
                from identity_access.ia_responsibility_reconciliation_run
               order by business_date desc, completed_at desc
               limit 1), 0)
            """;
    private static final String RESPONSIBILITY_MATCH_RATE_SQL = """
            select coalesce((
              select match_rate::double precision
                from identity_access.ia_responsibility_reconciliation_run
               order by business_date desc, completed_at desc
               limit 1), 0.0)
            """;
    private static final String RESPONSIBILITY_ACTIVE_UNMAPPED_SQL = """
            select coalesce((
              select active_unmapped_count
                from identity_access.ia_responsibility_reconciliation_run
               order by business_date desc, completed_at desc
               limit 1), 0)
            """;
    private static final String RESPONSIBILITY_OPEN_EXCEPTIONS_SQL = """
            select count(*)
              from identity_access.ia_responsibility_exception_current
             where status='open'
            """;
    private static final String RESPONSIBILITY_RETRY_BACKLOG_SQL = """
            select (
              (select count(*)
                 from identity_access.ia_identity_sync_job
                where consumer_projection='responsibility'
                  and status in ('queued', 'running'))
              +
              (select count(*)
                 from identity_access.ia_responsibility_reconciliation_job
                where status in ('queued', 'running'))
            )
            """;
    private static final String RESPONSIBILITY_QUARANTINE_SQL = """
            select count(*)
              from identity_access.ia_identity_rejected_record
             where consumer_projection='responsibility'
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
        registry.gauge(
                "responsibility_sync_last_success_epoch_seconds",
                jdbc,
                source -> doubleValue(
                        source,
                        RESPONSIBILITY_LAST_SUCCESS_SQL));
        registry.gauge(
                "responsibility_sync_watermark_lag",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_WATERMARK_LAG_SQL));
        registry.gauge(
                "responsibility_sync_slo_30d_numerator",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_SLO_NUMERATOR_SQL));
        registry.gauge(
                "responsibility_sync_slo_30d_denominator",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_SLO_DENOMINATOR_SQL));
        registry.gauge(
                "responsibility_sync_slo_30d_rate",
                jdbc,
                source -> doubleValue(
                        source,
                        RESPONSIBILITY_SLO_RATE_SQL));
        registry.gauge(
                "responsibility_reconciliation_daily_age_seconds",
                jdbc,
                source -> doubleValue(
                        source,
                        RESPONSIBILITY_DAILY_RUN_AGE_SQL));
        registry.gauge(
                "responsibility_reconciliation_quality_passed",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_DAILY_QUALITY_SQL));
        registry.gauge(
                "responsibility_reconciliation_match_rate",
                jdbc,
                source -> doubleValue(
                        source,
                        RESPONSIBILITY_MATCH_RATE_SQL));
        registry.gauge(
                "responsibility_reconciliation_active_unmapped",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_ACTIVE_UNMAPPED_SQL));
        registry.gauge(
                "responsibility_open_exceptions",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_OPEN_EXCEPTIONS_SQL));
        registry.gauge(
                "responsibility_retry_backlog",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_RETRY_BACKLOG_SQL));
        registry.gauge(
                "responsibility_quarantine_count",
                jdbc,
                source -> longValue(
                        source,
                        RESPONSIBILITY_QUARANTINE_SQL));
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
