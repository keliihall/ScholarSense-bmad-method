package cn.edu.suda.scholarsense.signalevaluation.adapters.outbound;

import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleNormalizedInputResolution;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleNormalizedInputResolverPort;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleResolutionCommand;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Resolves only sealed, currently effective normalized subject-window evidence. */
public final class JdbcRecoverySampleNormalizedInputResolver
        implements RecoverySampleNormalizedInputResolverPort {
    private final JdbcTemplate jdbc;
    private final RecoverySamplePersistenceJsonCodec codec;
    private final java.util.function.Supplier<Instant> time;

    public JdbcRecoverySampleNormalizedInputResolver(
            JdbcTemplate jdbc, ObjectMapper json, java.util.function.Supplier<Instant> time) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.codec = new RecoverySamplePersistenceJsonCodec(json);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Override
    public RecoverySampleNormalizedInputResolution resolve(RecoverySampleResolutionCommand command) {
        try {
            List<String> encoded = jdbc.query("""
                    select signal_evaluation.se_resolve_recovery_sample_normalized_input(?,?)::text
                    """, (row, ignored) -> row.getString(1),
                    command.opaqueSubjectWindowSelectionRef(), Timestamp.from(time.get()));
            if (encoded.isEmpty() || encoded.getFirst() == null) {
                return new RecoverySampleNormalizedInputResolution.UnknownBindings();
            }
            var root = codec.tree(encoded.getFirst());
            ArrayList<RecoverySampleNormalizedInputResolution.NormalizedStratum> strata =
                    new ArrayList<>();
            int total = 0;
            for (var item : root.required("strata")) {
                ArrayList<RecoverySampleNormalizedInputResolution.NormalizedSubjectWindow> windows =
                        new ArrayList<>();
                for (var window : item.required("selectedWindows")) {
                    if (++total > 10_000) {
                        return new RecoverySampleNormalizedInputResolution.UnknownBindings();
                    }
                    windows.add(new RecoverySampleNormalizedInputResolution.NormalizedSubjectWindow(
                            RecoverySamplePersistenceJsonCodec.text(window, "selectionRankDigest"),
                            RecoverySamplePersistenceJsonCodec.text(window, "expectedDigest"),
                            RecoverySamplePersistenceJsonCodec.text(
                                    window, "normalizedInputDigest")));
                }
                strata.add(new RecoverySampleNormalizedInputResolution.NormalizedStratum(
                        RecoverySamplePersistenceJsonCodec.text(item, "code"),
                        RecoverySamplePersistenceJsonCodec.number(item, "populationCount"),
                        windows));
            }
            return new RecoverySampleNormalizedInputResolution.Resolved(
                    RecoverySamplePersistenceJsonCodec.text(root, "ruleVersionsDigest"),
                    RecoverySamplePersistenceJsonCodec.text(root, "memberSetDigest"),
                    RecoverySamplePersistenceJsonCodec.text(root, "watermarksDigest"),
                    RecoverySamplePersistenceJsonCodec.text(root, "qualityRecoveryPolicyDigest"),
                    RecoverySamplePersistenceJsonCodec.text(root, "selectionSeed"), strata);
        } catch (RuntimeException failure) {
            return new RecoverySampleNormalizedInputResolution.Unavailable();
        }
    }
}
