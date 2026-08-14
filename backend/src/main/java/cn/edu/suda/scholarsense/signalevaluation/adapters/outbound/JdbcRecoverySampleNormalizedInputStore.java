package cn.edu.suda.scholarsense.signalevaluation.adapters.outbound;

import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleNormalizedInput;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleNormalizedInputPort;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Signal-evaluation owner adapter; consumer code never receives table privileges. */
public final class JdbcRecoverySampleNormalizedInputStore
        implements RecoverySampleNormalizedInputPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRecoverySampleNormalizedInputStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public void stage(RecoverySampleNormalizedInput value) {
        jdbc.query("""
                select signal_evaluation.se_stage_recovery_sample_normalized_input(
                    ?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """, row -> null, value.opaqueSelectionRef(), value.inputVersion(),
                value.ruleVersionsDigest(), value.memberSetDigest(), value.watermarksDigest(),
                value.qualityRecoveryPolicyDigest(), value.selectionSeed(),
                encode(value), value.strataDigest(), Timestamp.from(value.sealedAt()),
                Timestamp.from(value.effectiveAt()), Timestamp.from(value.expiresAt()));
    }

    private String encode(RecoverySampleNormalizedInput value) {
        var strata = value.strata().stream().map(stratum -> {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("code", stratum.code());
            item.put("populationCount", stratum.populationCount());
            item.put("selectedWindows", stratum.windows().stream().map(window -> Map.of(
                    "selectionRankDigest", window.selectionRankDigest(),
                    "expectedDigest", window.expectedDigest(),
                    "normalizedInputDigest", window.normalizedInputDigest())).toList());
            return item;
        }).toList();
        try {
            return json.writeValueAsString(strata);
        } catch (JacksonException malformed) {
            throw new IllegalStateException("RECOVERY_SAMPLE_INPUT_ENCODING_INVALID", malformed);
        }
    }
}
