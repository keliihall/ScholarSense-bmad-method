package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.VerificationRun;
import cn.edu.suda.scholarsense.auditoperations.application.VerificationRunRepository;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAuditVerificationRunRepository implements VerificationRunRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuditVerificationRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(VerificationRun run) {
        jdbc.update("""
                insert into audit_operations.ao_verification_run (
                  run_id, mode, start_sequence, end_sequence, verified_head_sequence,
                  verified_head_hash, healthy, started_at, completed_at, trace_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.runId(), run.mode(), run.startSequence(), run.endSequence(),
                run.verifiedHead().ledgerSequence(), run.verifiedHead().entryHash().value(),
                run.healthy(), Timestamp.from(run.startedAt()), Timestamp.from(run.completedAt()),
                run.traceId());
    }

    @Override
    public Optional<LedgerHead> lastHealthyWatermark() {
        return jdbc.query("""
                select verified_head_sequence, verified_head_hash
                from audit_operations.ao_verification_run
                where healthy=true
                order by completed_at desc, run_id desc limit 1
                """, (result, ignored) -> new LedgerHead(
                        result.getLong("verified_head_sequence"),
                        new LedgerHash(result.getString("verified_head_hash"))))
                .stream().findFirst();
    }
}
