package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionLeaseRepositoryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionAuthorizationLease;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Durable single-winner execution authorization lease store. */
public final class JdbcHighRiskExecutionLeaseRepository
        implements HighRiskExecutionLeaseRepositoryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HighRiskEvidenceJsonCodec codec;
    private final TransactionTemplate transactions;

    public JdbcHighRiskExecutionLeaseRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, null);
    }

    public JdbcHighRiskExecutionLeaseRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
        this.codec = new HighRiskEvidenceJsonCodec(json);
        this.transactions = transactionManager == null
                ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<LeaseReplay> findByIdempotencyKeyDigest(String digest) {
        return one("select identity_access.ia_find_high_risk_execution_lease_by_key(?)::text", digest)
                .map(this::replay);
    }

    @Override
    public Optional<HighRiskExecutionAuthorizationLease> findById(UUID leaseId) {
        return one("select identity_access.ia_find_high_risk_execution_lease_by_id(?)::text", leaseId)
                .map(codec::readLease);
    }

    @Override
    public Optional<HighRiskExecutionAuthorizationLease> findByApprovalId(UUID approvalId) {
        return one(
                "select identity_access.ia_find_high_risk_execution_lease_by_approval(?)::text",
                approvalId).map(codec::readLease);
    }

    @Override
    public LeaseReplay issueIfAbsent(
            String keyDigest, String issuanceDigest,
            HighRiskExecutionAuthorizationLease requested) {
        String value = jdbc.queryForObject("""
                select identity_access.ia_issue_high_risk_execution_lease(
                    ?,?,?::jsonb)::text
                """, String.class, keyDigest, issuanceDigest, codec.writeLease(requested));
        return replay(java.util.Objects.requireNonNull(value));
    }

    @Override
    public HighRiskExecutionAuthorizationLease save(
            long expectedVersion, HighRiskExecutionAuthorizationLease updated) {
        String value = jdbc.queryForObject("""
                select identity_access.ia_save_high_risk_execution_lease(?,?::jsonb)::text
                """, String.class, expectedVersion, codec.writeLease(updated));
        return codec.readLease(java.util.Objects.requireNonNull(value));
    }

    @Override
    public List<HighRiskExecutionAuthorizationLease> findExpirable(
            int limit, java.time.Instant trustedNow) {
        return jdbc.query("""
                select value::text
                  from identity_access.ia_find_expirable_high_risk_execution_leases(?,?) value
                """, (row, ignored) -> codec.readLease(row.getString(1)), limit, trustedNow);
    }

    @Override
    public void retireTerminal(UUID leaseId, long expectedVersion, String expectedDigest) {
        Boolean retired = jdbc.queryForObject("""
                select identity_access.ia_retire_terminal_high_risk_execution_lease(?,?,?)
                """, Boolean.class, leaseId, expectedVersion, expectedDigest);
        if (!Boolean.TRUE.equals(retired)) {
            throw new IllegalStateException("HIGH_RISK_EXECUTION_RETIRE_CONFLICT");
        }
    }

    @Override
    public <T> T inExpiryTransaction(java.util.function.Supplier<T> action) {
        if (transactions == null) return action.get();
        return transactions.execute(status -> action.get());
    }

    private LeaseReplay replay(String encoded) {
        try {
            var root = json.readTree(encoded);
            return new LeaseReplay(root.required("issuanceRequestDigest").asText(),
                    codec.readLease(json.writeValueAsString(root.required("lease"))));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "IDENTITY_HIGH_RISK_PERSISTED_VALUE_INVALID", failure);
        }
    }

    private Optional<String> one(String sql, Object argument) {
        List<String> values = jdbc.query(sql, (row, ignored) -> row.getString(1), argument);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }
}
