package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;

/** Exact, fail-closed translation of errors deliberately raised by V14 owner functions. */
final class DataBatchJdbcFailures {
    private static final Set<String> OWNER_SQL_STATES = Set.of(
            "23505", "23514", "40001", "42501", "P0001", "P0002");
    private static final Map<String, String> STABLE_CODES = Map.ofEntries(
            stable("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH"),
            stable("INGESTION_QUALITY_VERSION_CONFLICT"),
            stable("INGESTION_QUALITY_BATCH_NOT_FOUND"),
            stable("INGESTION_QUALITY_BATCH_IDENTITY_CONFLICT"),
            stable("INGESTION_QUALITY_SOURCE_VERSION_REGRESSION"),
            stable("INGESTION_QUALITY_CORRECTION_INVALID"),
            stable("INGESTION_QUALITY_CORRECTION_FORK"),
            stable("INGESTION_QUALITY_SEAL_EVIDENCE_INVALID"),
            stable("INGESTION_QUALITY_MEASUREMENT_SET_INCOMPLETE"),
            stable("INGESTION_QUALITY_MEASUREMENT_CONFLICT"),
            stable("INGESTION_QUALITY_MEASUREMENT_EVIDENCE_INVALID"),
            stable("INGESTION_QUALITY_MEASUREMENT_IMMUTABLE"),
            stable("INGESTION_QUALITY_MEASUREMENT_OPERAND_INVALID"),
            stable("INGESTION_QUALITY_MEASUREMENT_STATE_INVALID"),
            stable("INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID"),
            stable("INGESTION_QUALITY_NORMALIZED_FACT_CONFLICT"),
            stable("INGESTION_QUALITY_NORMALIZED_FACT_SCHEMA_INVALID"),
            stable("INGESTION_QUALITY_NORMALIZED_FACT_STATE_INVALID"),
            stable("INGESTION_QUALITY_IMPACT_SCOPE_CONFLICT"),
            stable("INGESTION_QUALITY_IMPACT_SCOPE_INVALID"),
            stable("INGESTION_QUALITY_IMPACT_SCOPE_IMMUTABLE"),
            stable("INGESTION_QUALITY_IMPACT_SCOPE_LIMIT_EXCEEDED"),
            stable("INGESTION_QUALITY_IMPACT_SCOPE_STATE_INVALID"),
            stable("INGESTION_QUALITY_BATCH_FACT_ACCUMULATOR_INVALID"),
            stable("INGESTION_QUALITY_SEALED_CONTRACT_INVALID"),
            stable("INGESTION_QUALITY_ASSESSMENT_FORMULA_SET_INVALID"),
            stable("INGESTION_QUALITY_ASSESSMENT_HASH_INVALID"),
            stable("INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID"),
            stable("INGESTION_QUALITY_ASSESSMENT_OVERALL_INVALID"),
            stable("INGESTION_QUALITY_ASSESSMENT_POLICY_BINDING_INVALID"),
            stable("INGESTION_QUALITY_ASSESSMENT_TRACE_INVALID"),
            stable("QUALITY_POLICY_ZERO_DENOMINATOR"),
            stable("INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID"),
            stable("INGESTION_QUALITY_PUBLISH_STATE_INVALID"),
            stable("INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID"),
            stable("INGESTION_QUALITY_AUDIT_PAYLOAD_DIGEST_MISMATCH"),
            stable("INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID"),
            stable("INGESTION_QUALITY_BUSINESS_PAYLOAD_NOT_CANONICAL"),
            stable("INGESTION_QUALITY_BUSINESS_PAYLOAD_DIGEST_MISMATCH"),
            Map.entry("INGESTION_QUALITY_BATCH_LINEAGE_BINDING_INVALID",
                    "INGESTION_QUALITY_CORRECTION_INVALID"),
            Map.entry("INGESTION_QUALITY_BATCH_LINEAGE_SELF_REFERENCE",
                    "INGESTION_QUALITY_CORRECTION_INVALID"),
            Map.entry("INGESTION_QUALITY_BATCH_LINEAGE_ROOT_CONFLICT",
                    "INGESTION_QUALITY_CORRECTION_FORK"),
            Map.entry("INGESTION_QUALITY_BATCH_LINEAGE_PREDECESSOR_NOT_HEAD",
                    "INGESTION_QUALITY_CORRECTION_FORK"),
            Map.entry("INGESTION_QUALITY_WORKLOAD_ROLE_MISMATCH",
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"),
            Map.entry("INGESTION_QUALITY_IDEMPOTENCY_COMPLETE_CONFLICT",
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"),
            stable("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));

    private DataBatchJdbcFailures() {}

    static RuntimeException translate(DataAccessException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!(current instanceof SQLException sql)
                    || !OWNER_SQL_STATES.contains(sql.getSQLState())) {
                continue;
            }
            String ownerCode = exactOwnerCode(sql.getMessage());
            if (ownerCode == null) continue;
            String stableCode = STABLE_CODES.get(ownerCode);
            if (stableCode != null) {
                return new IngestionQualityApplicationException(stableCode, failure);
            }
        }
        return failure;
    }

    private static String exactOwnerCode(String message) {
        if (message == null) return null;
        for (String code : STABLE_CODES.keySet()) {
            if (message.equals(code) || message.equals("ERROR: " + code)
                    || message.startsWith(code + "\n")
                    || message.startsWith("ERROR: " + code + "\n")) {
                return code;
            }
        }
        return null;
    }

    private static Map.Entry<String, String> stable(String code) {
        return Map.entry(code, code);
    }
}
