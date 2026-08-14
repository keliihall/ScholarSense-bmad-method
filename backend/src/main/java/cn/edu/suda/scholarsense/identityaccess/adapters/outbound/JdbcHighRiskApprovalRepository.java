package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.ApprovedHighRiskEvidenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalReceipt;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalRepositoryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Identity-access-owned approval persistence through closed, atomic database functions. */
public final class JdbcHighRiskApprovalRepository
        implements HighRiskApprovalRepositoryPort, ApprovedHighRiskEvidenceQueryPort {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json;
    private final HighRiskEvidenceJsonCodec codec;
    private final TransactionTemplate transactions;

    public JdbcHighRiskApprovalRepository(JdbcTemplate jdbc, tools.jackson.databind.ObjectMapper json) {
        this(jdbc, json, null);
    }

    public JdbcHighRiskApprovalRepository(
            JdbcTemplate jdbc,
            tools.jackson.databind.ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
        this.codec = new HighRiskEvidenceJsonCodec(json);
        this.transactions = transactionManager == null
                ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<HighRiskApproval> findByIdempotencyKeyDigest(String digest) {
        return one("select identity_access.ia_find_high_risk_approval_by_key(?)::text", digest)
                .map(codec::readApproval);
    }

    @Override
    public Optional<HighRiskApproval> findById(UUID approvalId) {
        return one("select identity_access.ia_find_high_risk_approval_by_id(?)::text", approvalId)
                .map(codec::readApproval);
    }

    @Override
    public HighRiskApproval insertIfAbsent(String digest, HighRiskApproval requested) {
        String value = jdbc.queryForObject(
                "select identity_access.ia_insert_high_risk_approval(?,?::jsonb)::text",
                String.class, digest, codec.writeApproval(requested));
        return codec.readApproval(java.util.Objects.requireNonNull(value));
    }

    @Override
    public HighRiskApproval save(
            long expectedVersion, HighRiskApproval updated, HighRiskApprovalReceipt receipt) {
        String value = jdbc.queryForObject(
                "select identity_access.ia_save_high_risk_approval(?,?::jsonb,?::jsonb)::text",
                String.class, expectedVersion, codec.writeApproval(updated),
                receipt == null ? null : codec.writeReceipt(receipt));
        return codec.readApproval(java.util.Objects.requireNonNull(value));
    }

    @Override
    public Optional<DecisionReplay> findDecisionByIdempotencyKeyDigest(String digest) {
        return one("select identity_access.ia_find_high_risk_approval_decision_by_key(?)::text",
                digest).map(this::decisionReplay);
    }

    @Override
    public DecisionReplay saveDecisionIfAbsent(
            String idempotencyKeyDigest,
            String inputDigest,
            long expectedApprovalVersion,
            HighRiskApproval updated,
            HighRiskApprovalReceipt terminalReceipt) {
        String value = jdbc.queryForObject("""
                select identity_access.ia_save_high_risk_approval_decision(
                    ?,?,?,?::jsonb,?::jsonb)::text
                """, String.class, idempotencyKeyDigest, inputDigest,
                expectedApprovalVersion, codec.writeApproval(updated),
                terminalReceipt == null ? null : codec.writeReceipt(terminalReceipt));
        return decisionReplay(java.util.Objects.requireNonNull(value));
    }

    @Override
    public Optional<HighRiskApprovalReceipt> findReceipt(UUID approvalId) {
        return one("select identity_access.ia_find_high_risk_approval_receipt(?)::text", approvalId)
                .map(codec::readReceipt);
    }

    @Override
    public List<HighRiskApproval> findExpirable(int limit, Instant trustedNow) {
        return jdbc.query(
                "select value::text from identity_access.ia_find_expirable_high_risk_approvals(?,?) value",
                (row, ignored) -> codec.readApproval(row.getString(1)), limit, trustedNow);
    }

    @Override
    public <T> T inExpiryTransaction(java.util.function.Supplier<T> action) {
        if (transactions == null) return action.get();
        return transactions.execute(status -> action.get());
    }

    @Override
    public Optional<ApprovedEvidence> findApproved(UUID approvalId) {
        HighRiskApproval approval = findById(approvalId).orElse(null);
        if (approval == null || approval.status() != HighRiskApprovalStatus.APPROVED) {
            return Optional.empty();
        }
        return findReceipt(approvalId).filter(receipt ->
                receipt.approvalVersion() == approval.approvalVersion())
                .map(receipt -> new ApprovedEvidence(approval, receipt));
    }

    private Optional<String> one(String sql, Object argument) {
        List<String> values = jdbc.query(sql, (row, ignored) -> row.getString(1), argument);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    private DecisionReplay decisionReplay(String encoded) {
        try {
            var root = json.readTree(encoded);
            var receipt = root.required("receiptDigest");
            return new DecisionReplay(root.required("inputDigest").asText(),
                    codec.readApproval(root.required("approval").toString()),
                    receipt.isNull() ? null : receipt.asText());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "IDENTITY_HIGH_RISK_PERSISTED_VALUE_INVALID", failure);
        }
    }
}
