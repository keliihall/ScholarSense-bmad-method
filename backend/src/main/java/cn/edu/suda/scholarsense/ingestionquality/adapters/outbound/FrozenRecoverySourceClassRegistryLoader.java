package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoverySourceClass;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoverySourceClassRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads only the exact raw-SHA-approved QRSCR bindings; all other sources fail closed. */
public final class FrozenRecoverySourceClassRegistryLoader {
    private static final String RELATIVE =
            "ingestion-quality/quality-recovery/recovery-source-class-registry-1.0.0.json";

    private FrozenRecoverySourceClassRegistryLoader() {}

    public static QualityRecoverySourceClassRegistry load(
            Path contractRoot,
            ObjectMapper json) {
        Objects.requireNonNull(contractRoot);
        Objects.requireNonNull(json);
        try {
            byte[] raw = Files.readAllBytes(contractRoot.resolve(RELATIVE));
            verifyDigest(raw, QualityRecoverySourceClassRegistry.RAW_DIGEST);
            JsonNode root = json.readTree(raw);
            JsonNode approval = object(root, "approval");
            String proposedDigest = text(root, "proposedBindingSetDigest");
            String approvedDigest = text(approval, "approvedBindingSetDigest");
            if (!proposedDigest.equals(approvedDigest)) throw invalid();

            String approvalRef = text(approval, "approvalRef");
            String approvedBy = text(approval, "approvedBy");
            OffsetDateTime effectiveAt = time(approval, "effectiveAt");
            Map<String, QualityRecoverySourceClass> bindings = new LinkedHashMap<>();
            JsonNode sources = root.get("sources");
            if (sources == null || !sources.isArray()) throw invalid();
            for (JsonNode source : sources) {
                if (!source.isObject()
                        || !approvalRef.equals(text(source, "approvalRef"))
                        || !approvedBy.equals(text(source, "approvedBy"))
                        || !effectiveAt.equals(time(source, "effectiveAt"))) {
                    throw invalid();
                }
                String sourceId = text(source, "sourceId");
                QualityRecoverySourceClass sourceClass =
                        QualityRecoverySourceClass.fromContractValue(
                                text(source, "sourceClass"));
                if (bindings.put(sourceId, sourceClass) != null) throw invalid();
            }

            return new QualityRecoverySourceClassRegistry(
                    text(root, "registryVersion"),
                    QualityRecoverySourceClassRegistry.RAW_DIGEST,
                    text(root, "authority"),
                    text(root, "status"),
                    text(root, "activation"),
                    text(root, "classificationRule"),
                    text(root, "proposalBasis"),
                    text(root, "bindingCanonicalization"),
                    proposedDigest,
                    approvalRef,
                    approvedBy,
                    time(approval, "approvedAt"),
                    effectiveAt,
                    bindings,
                    text(root, "unknownSource"),
                    text(root, "duplicateSource"),
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

    private static OffsetDateTime time(JsonNode parent, String field) {
        return OffsetDateTime.parse(text(parent, field));
    }

    private static void verifyDigest(byte[] raw, String expected) throws Exception {
        String actual = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw));
        if (!expected.equals(actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "QUALITY_RECOVERY_SOURCE_CLASS_REGISTRY_INVALID");
    }
}
