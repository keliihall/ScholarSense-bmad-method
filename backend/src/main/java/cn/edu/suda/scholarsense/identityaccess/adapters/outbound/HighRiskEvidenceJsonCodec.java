package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalReceipt;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionAuthorizationLease;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionLeaseState;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionToken;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit owner-private codec; persisted aggregates never depend on reflection defaults. */
final class HighRiskEvidenceJsonCodec {
    private final ObjectMapper json;

    HighRiskEvidenceJsonCodec(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json);
    }

    String writeApproval(HighRiskApproval value) {
        HighRiskApprovalBinding binding = value.binding();
        Map<String, Object> out = binding(binding);
        out.put("approvalId", value.approvalId().toString());
        out.put("approvalVersion", value.approvalVersion());
        out.put("status", wire(value.status()));
        out.put("requestedAt", value.requestedAt().toString());
        out.put("expiresAt", value.expiresAt().toString());
        out.put("approvedCheckerDigests", value.approvedCheckerDigests().stream().sorted().toList());
        out.put("decisionActorPrincipalDigest", value.decisionActorPrincipalDigest());
        out.put("decidedAt", value.decidedAt() == null ? null : value.decidedAt().toString());
        return write(out);
    }

    HighRiskApproval readApproval(String encoded) {
        try {
            JsonNode root = json.readTree(encoded);
            HighRiskApprovalBinding binding = binding(root);
            return HighRiskApproval.restore(
                    uuid(root, "approvalId"), binding, instant(root, "requestedAt"),
                    number(root, "approvalVersion"),
                    HighRiskApprovalStatus.valueOf(text(root, "status").toUpperCase()),
                    Set.copyOf(strings(root, "approvedCheckerDigests")),
                    nullableText(root, "decisionActorPrincipalDigest"),
                    nullableInstant(root, "decidedAt"));
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    String writeReceipt(HighRiskApprovalReceipt value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", value.approvalId().toString());
        out.put("approvalVersion", value.approvalVersion());
        out.put("requestId", value.requestId().toString());
        out.put("requestDigest", value.requestDigest());
        out.put("status", wire(value.status()));
        out.put("decisionActorPrincipalDigest", value.decisionActorPrincipalDigest());
        out.put("decidedAt", value.decidedAt().toString());
        out.put("expiresAt", value.expiresAt().toString());
        out.put("receiptDigest", value.receiptDigest());
        out.put("keyVersion", value.keyVersion());
        out.put("signature", value.signature());
        out.put("traceId", value.traceId());
        return write(out);
    }

    HighRiskApprovalReceipt readReceipt(String encoded) {
        try {
            JsonNode root = json.readTree(encoded);
            return new HighRiskApprovalReceipt(
                    uuid(root, "approvalId"), number(root, "approvalVersion"),
                    uuid(root, "requestId"), text(root, "requestDigest"),
                    HighRiskApprovalStatus.valueOf(text(root, "status").toUpperCase()),
                    text(root, "decisionActorPrincipalDigest"), instant(root, "decidedAt"),
                    instant(root, "expiresAt"), text(root, "receiptDigest"),
                    text(root, "keyVersion"), text(root, "signature"), text(root, "traceId"));
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    String writeLease(HighRiskExecutionAuthorizationLease value) {
        HighRiskExecutionToken token = value.token();
        Map<String, Object> out = binding(token.binding());
        out.put("leaseId", value.leaseId().toString());
        out.put("leaseVersion", value.leaseVersion());
        out.put("leaseDigest", value.leaseDigest());
        out.put("executionJti", value.executionJti().toString());
        out.put("approvalId", token.approvalId().toString());
        out.put("approvalVersion", token.approvalVersion());
        out.put("approvalReceiptDigest", token.approvalReceiptDigest());
        out.put("tokenJti", token.tokenJti().toString());
        out.put("state", wire(value.state()));
        out.put("issuedAt", token.issuedAt().toString());
        out.put("authorizedUntil", token.authorizedUntil().toString());
        out.put("audience", token.audience());
        out.put("tokenKeyVersion", token.keyVersion());
        out.put("tokenSignature", token.signature());
        out.put("keyVersion", value.keyVersion());
        out.put("signature", value.signature());
        out.put("reservedAt", string(value.reservedAt()));
        out.put("ownerCommittedAt", string(value.ownerCommittedAt()));
        out.put("confirmedAt", string(value.confirmedAt()));
        out.put("ownerCommitId", value.ownerCommitId());
        out.put("ownerResultDigest", value.ownerResultDigest());
        out.put("outboxEventId", value.outboxEventId() == null
                ? null : value.outboxEventId().toString());
        return write(out);
    }

    HighRiskExecutionAuthorizationLease readLease(String encoded) {
        try {
            JsonNode root = json.readTree(encoded);
            HighRiskApprovalBinding binding = binding(root);
            HighRiskExecutionToken token = new HighRiskExecutionToken(
                    uuid(root, "tokenJti"), uuid(root, "approvalId"),
                    number(root, "approvalVersion"), text(root, "approvalReceiptDigest"),
                    binding, instant(root, "issuedAt"), instant(root, "authorizedUntil"),
                    text(root, "audience"), text(root, "tokenKeyVersion"),
                    text(root, "tokenSignature"));
            return HighRiskExecutionAuthorizationLease.restore(
                    uuid(root, "leaseId"), number(root, "leaseVersion"),
                    text(root, "leaseDigest"), uuid(root, "executionJti"), token,
                    text(root, "keyVersion"), text(root, "signature"),
                    HighRiskExecutionLeaseState.valueOf(text(root, "state").toUpperCase()),
                    nullableInstant(root, "reservedAt"),
                    nullableInstant(root, "ownerCommittedAt"),
                    nullableInstant(root, "confirmedAt"), nullableText(root, "ownerCommitId"),
                    nullableText(root, "ownerResultDigest"), nullableUuid(root, "outboxEventId"));
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    private static Map<String, Object> binding(HighRiskApprovalBinding value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", value.requestId().toString());
        out.put("requestDigest", value.requestDigest());
        out.put("actionType", value.actionType());
        out.put("makerPrincipalDigest", value.makerPrincipalDigest());
        out.put("authorizationContextDigest", value.authorizationContextDigest());
        out.put("authenticationStateDigest", value.authenticationStateDigest());
        out.put("objectType", value.objectType());
        out.put("objectRefDigest", value.objectRefDigest());
        out.put("objectVersion", value.objectVersion());
        out.put("scopeDigest", value.scopeDigest());
        out.put("impactScopeDigest", value.impactScopeDigest());
        out.put("dataSensitivity", value.dataSensitivity().name().toLowerCase().replace('_', '-'));
        out.put("currentState", value.currentState());
        out.put("targetState", value.targetState());
        out.put("reasonCode", value.reasonCode());
        out.put("matrixVersion", value.matrixVersion());
        out.put("matrixDigest", value.matrixDigest());
        out.put("policyVersion", value.policyVersion());
        out.put("policyDigest", value.policyDigest());
        out.put("roleFieldPolicyVersion", value.roleFieldPolicyVersion());
        out.put("roleFieldPolicyDigest", value.roleFieldPolicyDigest());
        out.put("previewDigest", value.previewDigest());
        out.put("checkerSetDigest", value.checkerSetDigest());
        out.put("requiredCheckerPrincipalDigests", value.requiredCheckerPrincipalDigests());
        out.put("authorizationGeneration", value.authorizationGeneration());
        out.put("traceId", value.traceId());
        return out;
    }

    private static HighRiskApprovalBinding binding(JsonNode root) {
        return new HighRiskApprovalBinding(
                uuid(root, "requestId"), text(root, "requestDigest"), text(root, "actionType"),
                text(root, "makerPrincipalDigest"), text(root, "authorizationContextDigest"),
                text(root, "authenticationStateDigest"), text(root, "objectType"),
                text(root, "objectRefDigest"), number(root, "objectVersion"),
                text(root, "scopeDigest"), text(root, "impactScopeDigest"),
                HighRiskApprovalBinding.DataSensitivity.valueOf(
                        text(root, "dataSensitivity").replace('-', '_').toUpperCase()),
                text(root, "currentState"), text(root, "targetState"),
                text(root, "reasonCode"), text(root, "matrixVersion"),
                text(root, "matrixDigest"), text(root, "policyVersion"),
                text(root, "policyDigest"), text(root, "roleFieldPolicyVersion"),
                text(root, "roleFieldPolicyDigest"), text(root, "previewDigest"),
                text(root, "checkerSetDigest"), strings(root, "requiredCheckerPrincipalDigests"),
                number(root, "authorizationGeneration"), text(root, "traceId"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw invalid(failure);
        }
    }

    private static String text(JsonNode root, String name) {
        JsonNode node = root.required(name);
        if (!node.isTextual()) throw invalid(null);
        return node.asText();
    }

    private static String nullableText(JsonNode root, String name) {
        JsonNode node = root.required(name);
        return node.isNull() ? null : text(root, name);
    }

    private static long number(JsonNode root, String name) {
        JsonNode node = root.required(name);
        if (!node.isIntegralNumber()) throw invalid(null);
        return node.asLong();
    }

    private static UUID uuid(JsonNode root, String name) {
        return UUID.fromString(text(root, name));
    }

    private static UUID nullableUuid(JsonNode root, String name) {
        String value = nullableText(root, name);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(JsonNode root, String name) {
        return Instant.parse(text(root, name));
    }

    private static Instant nullableInstant(JsonNode root, String name) {
        String value = nullableText(root, name);
        return value == null ? null : Instant.parse(value);
    }

    private static List<String> strings(JsonNode root, String name) {
        JsonNode node = root.required(name);
        if (!node.isArray() || node.size() > 128) throw invalid(null);
        ArrayList<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) throw invalid(null);
            values.add(value.asText());
        });
        return List.copyOf(values);
    }

    private static String string(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static IllegalArgumentException invalid(Throwable failure) {
        return new IllegalArgumentException("IDENTITY_HIGH_RISK_PERSISTED_VALUE_INVALID", failure);
    }
}
