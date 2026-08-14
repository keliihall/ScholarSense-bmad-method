package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.Backfill;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.BatchQualification;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.FailureFallback;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.ImpactPreview;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.QualityGate;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.Reconciliation;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.Sampling;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.SourceClassBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy.SourceClassRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoverySourceClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads only the exact raw-SHA-approved QRP-1.0.0 artifact. */
public final class FrozenQualityRecoveryPolicyLoader {
    private static final String RELATIVE =
            "ingestion-quality/quality-recovery/quality-recovery-policy-1.0.0.json";

    private FrozenQualityRecoveryPolicyLoader() {}

    public static QualityRecoveryPolicy load(Path contractRoot, ObjectMapper json) {
        Objects.requireNonNull(contractRoot);
        Objects.requireNonNull(json);
        try {
            byte[] raw = Files.readAllBytes(contractRoot.resolve(RELATIVE));
            verifyDigest(raw, QualityRecoveryPolicy.RAW_DIGEST);
            JsonNode root = json.readTree(raw);

            JsonNode binding = object(root, "sourceClassBinding");
            Map<QualityRecoverySourceClass, SourceClassRule> sourceClasses =
                    new EnumMap<>(QualityRecoverySourceClass.class);
            JsonNode classObject = object(root, "sourceClasses");
            classObject.properties().forEach(entry -> {
                QualityRecoverySourceClass sourceClass =
                        QualityRecoverySourceClass.fromContractValue(entry.getKey());
                JsonNode value = entry.getValue();
                if (!value.isObject() || sourceClasses.put(
                        sourceClass,
                        new SourceClassRule(
                                integer(value, "consecutivePassedBatches"),
                                Duration.ofMinutes(integer(value, "observationMinutes")))) != null) {
                    throw invalid();
                }
            });

            JsonNode qualification = object(root, "batchQualification");
            JsonNode backfill = object(root, "backfill");
            JsonNode reconciliation = object(root, "reconciliation");
            JsonNode sampling = object(root, "sampling");
            JsonNode qualityGate = object(root, "qualityGate");
            JsonNode preview = object(root, "impactPreview");
            JsonNode fallback = object(root, "failureFallback");

            return new QualityRecoveryPolicy(
                    text(root, "policyVersion"),
                    QualityRecoveryPolicy.RAW_DIGEST,
                    strings(root, "authority"),
                    new SourceClassBinding(
                            text(binding, "owner"),
                            text(binding, "source"),
                            text(binding, "unknown")),
                    sourceClasses,
                    new BatchQualification(
                            text(qualification, "acceptedPair"),
                            strings(qualification, "sequenceKey"),
                            Set.copyOf(strings(qualification, "forbiddenOrderingFields")),
                            bool(qualification, "inclusive")),
                    new Backfill(
                            Duration.ofDays(integer(backfill, "lookbackDays")),
                            text(backfill, "startExpression"),
                            bool(backfill, "trustedNowRequired")),
                    new Reconciliation(
                            text(reconciliation, "coverage"),
                            integer(reconciliation, "expectedMismatchCount")),
                    new Sampling(
                            text(sampling, "unit"),
                            bool(sampling, "stratified"),
                            integer(sampling, "minimumSubjectWindows"),
                            bool(sampling, "allIfPopulationFewer"),
                            integer(sampling, "expectedMismatchCount"),
                            bool(sampling, "selectionSeedRequired")),
                    new QualityGate(
                            text(qualityGate, "version"),
                            bool(qualityGate, "exactDigestRequired"),
                            text(qualityGate, "thresholdComparison"),
                            text(qualityGate, "requiredDependencies")),
                    new ImpactPreview(
                            bool(preview, "trustedMicrosecondTime"),
                            strings(preview, "categories"),
                            text(preview, "finalActionabilityOwnerStory"),
                            bool(preview, "authorizesExecution")),
                    new FailureFallback(
                            text(fallback, "qualificationFailure"),
                            Duration.ofHours(integer(fallback, "observationRelapseWithinHours")),
                            bool(fallback, "createsBusinessObjects")),
                    text(root, "runtimeEvidenceClaim"));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw invalid();
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.stringValue().isBlank()) throw invalid();
        return value.stringValue();
    }

    private static int integer(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private static boolean bool(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static List<String> strings(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) throw invalid();
        ArrayList<String> result = new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.stringValue().isBlank()
                    || !unique.add(element.stringValue())) {
                throw invalid();
            }
            result.add(element.stringValue());
        }
        return List.copyOf(result);
    }

    private static void verifyDigest(byte[] raw, String expected) throws Exception {
        String actual = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw));
        if (!expected.equals(actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_POLICY_INVALID");
    }
}
