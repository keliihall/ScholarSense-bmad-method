package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyMember;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyRegistry;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads the digest-locked registry instance; the domain oracle rejects semantic drift. */
public final class FrozenRuleDependencyRegistryLoader {
    private static final String RELATIVE =
            "ingestion-quality/rule-dependency/rule-dependency-registry-1.0.0.json";
    private static final String RAW_DIGEST =
            "0c676d796dd65c1842ed041544c5f731d70f045708984415bd74bfd5ace25e61";
    private static final String CANONICAL_DIGEST =
            "sha256:cd1915107c0c2657a430c2d5ba0abd420d9bd8d06f6cdcaed2a570c71ce6655a";

    private FrozenRuleDependencyRegistryLoader() {}

    public static RuleDependencyRegistry load(Path contractRoot, ObjectMapper json) {
        Objects.requireNonNull(contractRoot);
        Objects.requireNonNull(json);
        try {
            byte[] raw = Files.readAllBytes(contractRoot.resolve(RELATIVE));
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw));
            if (!RAW_DIGEST.equals(digest)) throw invalid();
            JsonNode root = json.readTree(raw);
            ArrayList<RuleDependencyDefinition> rules = new ArrayList<>();
            for (JsonNode rule : requiredArray(root, "rules")) {
                ArrayList<RuleDependencyMember> members = new ArrayList<>();
                for (JsonNode member : requiredArray(rule, "members")) {
                    members.add(new RuleDependencyMember(
                            text(member, "sourceId"), text(member, "sourceVersion"),
                            text(member, "dependencyId"), text(member, "dependencyVersion"),
                            requirement(text(member, "requirement")),
                            text(member, "compositionGroup")));
                }
                JsonNode composition = rule.get("composition");
                if (composition == null || !composition.isObject()) throw invalid();
                JsonNode threshold = composition.get("threshold");
                rules.add(new RuleDependencyDefinition(
                        new RuleVersionIdentity(
                                text(rule, "ruleId"), text(rule, "ruleVersion")),
                        operator(text(composition, "operator")),
                        threshold == null || threshold.isNull() ? null : threshold.intValue(),
                        members));
            }
            return new RuleDependencyRegistry(
                    text(root, "registryVersion"), CANONICAL_DIGEST,
                    text(requiredObject(root, "catalog"), "version"),
                    text(requiredObject(root, "catalog"), "digest"),
                    text(requiredObject(root, "ruleCatalog"), "version"),
                    text(requiredObject(root, "ruleCatalog"), "digest"), rules);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw invalid();
        return value;
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) throw invalid();
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.stringValue().isBlank()) throw invalid();
        return value.stringValue();
    }

    private static DependencyOperator operator(String value) {
        return switch (value) {
            case "all-of" -> DependencyOperator.ALL_OF;
            case "any-of" -> DependencyOperator.ANY_OF;
            case "threshold" -> DependencyOperator.THRESHOLD;
            default -> throw invalid();
        };
    }

    private static DependencyRequirement requirement(String value) {
        return switch (value) {
            case "required" -> DependencyRequirement.REQUIRED;
            case "optional" -> DependencyRequirement.OPTIONAL;
            default -> throw invalid();
        };
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_REGISTRY_INVALID");
    }
}
