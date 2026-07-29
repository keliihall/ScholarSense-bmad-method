package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityReconciliationPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityReconciliationResult;
import java.sql.Timestamp;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIdentityReconciliationAdapter
        implements IdentityReconciliationPort {
    private final JdbcTemplate jdbc;

    public JdbcIdentityReconciliationAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(IdentityReconciliationResult result) {
        jdbc.update("""
                insert into identity_access.ia_identity_reconciliation_sample (
                  reconciliation_id, source_id, scope, source_version,
                  source_watermark, expected_digest, actual_digest,
                  expected_count, actual_count, matched_count, missing_count,
                  unexpected_count, version_drift_count, generated_at, trace_id,
                  mutation_applied, retention_effective_at, expires_at)
                values (?, 'SRC-P0-RESPONSIBILITY-001', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, false, ?, ?)
                """,
                result.reconciliationId(),
                result.scope(),
                result.sourceVersion(),
                result.watermark(),
                result.expectedDigest(),
                result.actualDigest(),
                result.expectedCount(),
                result.actualCount(),
                result.matched(),
                result.missing(),
                result.unexpected(),
                result.versionDrift(),
                Timestamp.from(result.generatedAt()),
                result.traceId(),
                Timestamp.from(result.generatedAt()),
                Timestamp.from(result.generatedAt().plus(Duration.ofDays(365))));
    }
}
