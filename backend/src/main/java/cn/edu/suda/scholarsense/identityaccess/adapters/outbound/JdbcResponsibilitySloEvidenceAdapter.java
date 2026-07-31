package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySloEvidence;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySloEvidencePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySloWindow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable responsibility SLO evidence and independent compensation queue. */
public final class JdbcResponsibilitySloEvidenceAdapter
        implements ResponsibilitySloEvidencePort {
    private static final Duration RETENTION =
            Duration.ofDays(1095);

    private final JdbcTemplate jdbc;

    public JdbcResponsibilitySloEvidenceAdapter(
            JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public void append(ResponsibilitySloEvidence evidence) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_slo_evidence (
                  evidence_id, relation_id, student_ref_digest,
                  source_version, source_watermark, aggregate_version,
                  source_visible_at, applied_at,
                  authorization_effective_at, within_fifteen_minutes,
                  late_reason_code, trace_id, consumer_watermark,
                  retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (
                  relation_id, source_version,
                  source_watermark, aggregate_version)
                  do nothing
                """,
                evidence.evidenceId(),
                evidence.relationId(),
                evidence.studentSourceRefDigest(),
                evidence.sourceVersion(),
                evidence.sourceWatermark(),
                evidence.aggregateVersion(),
                timestamp(evidence.sourceVisibleAt()),
                timestamp(evidence.appliedAt()),
                timestamp(evidence.authorizationEffectiveAt()),
                evidence.withinFifteenMinutes(),
                evidence.lateReasonCode(),
                evidence.traceId(),
                evidence.sourceWatermark(),
                timestamp(evidence.authorizationEffectiveAt()),
                timestamp(evidence.authorizationEffectiveAt()
                        .plus(RETENTION)));
    }

    @Override
    public void compensate(
            ResponsibilitySloEvidence evidence,
            String reasonCode) {
        jdbc.update("""
                insert into identity_access.ia_responsibility_slo_compensation (
                  evidence_id, relation_id, student_ref_digest,
                  source_version, source_watermark, aggregate_version,
                  source_visible_at, applied_at,
                  attempted_authorization_effective_at,
                  within_fifteen_minutes, late_reason_code, reason_code,
                  requested_at, completed_at, trace_id,
                  consumer_watermark, retention_effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?,
                        ?, ?, ?)
                on conflict (evidence_id) do nothing
                """,
                evidence.evidenceId(),
                evidence.relationId(),
                evidence.studentSourceRefDigest(),
                evidence.sourceVersion(),
                evidence.sourceWatermark(),
                evidence.aggregateVersion(),
                timestamp(evidence.sourceVisibleAt()),
                timestamp(evidence.appliedAt()),
                timestamp(evidence.authorizationEffectiveAt()),
                evidence.withinFifteenMinutes(),
                evidence.lateReasonCode(),
                reasonCode,
                timestamp(evidence.authorizationEffectiveAt()),
                evidence.traceId(),
                evidence.sourceWatermark(),
                timestamp(evidence.authorizationEffectiveAt()),
                timestamp(evidence.authorizationEffectiveAt()
                        .plus(RETENTION)));
    }

    @Override
    public Optional<ResponsibilitySloEvidence>
            nextCompensation() {
        List<ResponsibilitySloEvidence> values = jdbc.query("""
                select evidence_id, relation_id, student_ref_digest,
                       source_version, source_watermark,
                       aggregate_version, source_visible_at, applied_at,
                       attempted_authorization_effective_at,
                       within_fifteen_minutes, late_reason_code, trace_id
                  from identity_access.ia_responsibility_slo_compensation
                 where status='pending'
                 order by requested_at, evidence_id
                 limit 1
                """,
                (rs, row) -> new ResponsibilitySloEvidence(
                        rs.getObject("evidence_id", UUID.class),
                        rs.getObject("relation_id", UUID.class),
                        rs.getString("student_ref_digest"),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getLong("aggregate_version"),
                        rs.getTimestamp("source_visible_at")
                                .toInstant(),
                        rs.getTimestamp("applied_at").toInstant(),
                        rs.getTimestamp(
                                        "attempted_authorization_effective_at")
                                .toInstant(),
                        rs.getBoolean("within_fifteen_minutes"),
                        rs.getString("late_reason_code"),
                        rs.getString("trace_id")));
        return values.stream().findFirst();
    }

    @Override
    public void completeCompensation(
            UUID evidenceId, Instant completedAt) {
        jdbc.update("""
                update identity_access.ia_responsibility_slo_compensation
                   set status='completed', completed_at=?
                 where evidence_id=? and status='pending'
                """,
                timestamp(completedAt),
                evidenceId);
    }

    @Override
    public ResponsibilitySloWindow rollingThirtyDays(
            Instant now) {
        Instant from = now.minus(Duration.ofDays(30));
        return jdbc.queryForObject("""
                select coalesce(sum(numerator), 0) as numerator,
                       coalesce(sum(denominator), 0) as denominator
                  from (
                    select count(*) filter (
                               where within_fifteen_minutes) as numerator,
                           count(*) as denominator
                      from identity_access.ia_responsibility_slo_evidence
                     where authorization_effective_at>=?
                    union all
                    select 0 as numerator, count(*) as denominator
                      from identity_access.ia_responsibility_slo_compensation
                             compensation
                     where status='pending' and requested_at>=?
                       and not exists (
                         select 1
                           from identity_access.ia_responsibility_slo_evidence
                                evidence
                          where evidence.evidence_id=
                                compensation.evidence_id)
                  ) window_counts
                """,
                (rs, row) -> new ResponsibilitySloWindow(
                        rs.getLong("numerator"),
                        rs.getLong("denominator")),
                timestamp(from),
                timestamp(from));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
