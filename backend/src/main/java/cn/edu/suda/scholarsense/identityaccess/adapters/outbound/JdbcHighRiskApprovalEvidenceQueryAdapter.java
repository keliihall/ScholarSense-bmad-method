package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidenceQueryPort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Trusted current approval projection for RECOVERY_TASK authorization evidence. */
public final class JdbcHighRiskApprovalEvidenceQueryAdapter
        implements HighRiskApprovalEvidenceQueryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcHighRiskApprovalEvidenceQueryAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public HighRiskApprovalEvidence query(HighRiskApprovalEvidenceQuery value) {
        try {
            List<String> encoded = jdbc.query("""
                    select identity_access.ia_find_current_high_risk_approval_evidence(
                        ?,?,?,?, ?,?)::text
                    """, (row, ignored) -> row.getString(1), value.actionType(),
                    value.objectType(), value.objectRefDigest(), value.objectVersion(),
                    value.actorAccountId(), value.trustedNow());
            if (encoded.isEmpty() || encoded.getFirst() == null) {
                return new HighRiskApprovalEvidence(
                        HighRiskApprovalEvidence.Availability.AVAILABLE,
                        null, 0, null, 0, "APPROVAL_NOT_CURRENT");
            }
            var root = json.readTree(encoded.getFirst());
            return new HighRiskApprovalEvidence(
                    HighRiskApprovalEvidence.Availability.AVAILABLE,
                    root.required("status").asText(), root.required("approvalVersion").asLong(),
                    root.required("receiptDigest").asText(),
                    root.required("authorizationGeneration").asLong(), null);
        } catch (RuntimeException failure) {
            return new HighRiskApprovalEvidence(
                    HighRiskApprovalEvidence.Availability.UNAVAILABLE,
                    null, 0, null, 0, "APPROVAL_PROVIDER_UNAVAILABLE");
        }
    }
}
