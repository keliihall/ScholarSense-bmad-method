package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionFieldResult;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionPort;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionResult;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionValueReference;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.identityaccess.api.SensitiveFieldValueResolverPort;
import cn.edu.suda.scholarsense.identityaccess.api.SensitiveProjectionAuditPort;
import cn.edu.suda.scholarsense.identityaccess.api.SensitiveProjectionAuditRecord;
import cn.edu.suda.scholarsense.identityaccess.application.FieldCiphertextEnvelope;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldCryptoContext;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldCryptoService;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveFieldKeyStatePort;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveReadTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.domain.ApprovedFieldDescriptor;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthorizationEvidenceVersions;
import cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveWindow;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldClass;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ProjectedFieldDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ProjectionObjectClass;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import cn.edu.suda.scholarsense.identityaccess.domain.ScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Current server-side field projection boundary. It never accepts a client-supplied decision. */
public final class CurrentFieldProjectionService implements FieldProjectionPort {
    private final CompositeAuthorizationPort authorizationPort;
    private final CompositeAuthorizationRecheckPort recheckPort;
    private final FieldProjectionEvaluator evaluator;
    private final FieldProjectionCatalog catalog;
    private final RoleFieldPolicyCatalog rolePolicy;
    private final SensitiveFieldValueResolverPort valueResolver;
    private final SensitiveFieldCryptoService cryptoService;
    private final SensitiveFieldKeyStatePort keyStatePort;
    private final SensitiveProjectionAuditPort auditPort;
    private final SensitiveReadTransactionPort transactionPort;
    private final String serviceIdentity;
    private final String environment;

    public CurrentFieldProjectionService(
            CompositeAuthorizationPort authorizationPort,
            CompositeAuthorizationRecheckPort recheckPort,
            FieldProjectionEvaluator evaluator,
            FieldProjectionCatalog catalog,
            RoleFieldPolicyCatalog rolePolicy,
            SensitiveFieldValueResolverPort valueResolver,
            SensitiveFieldCryptoService cryptoService,
            SensitiveFieldKeyStatePort keyStatePort,
            SensitiveProjectionAuditPort auditPort,
            SensitiveReadTransactionPort transactionPort,
            String serviceIdentity,
            String environment) {
        this.authorizationPort = Objects.requireNonNull(authorizationPort, "authorizationPort");
        this.recheckPort = Objects.requireNonNull(recheckPort, "recheckPort");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.rolePolicy = Objects.requireNonNull(rolePolicy, "rolePolicy");
        this.valueResolver = Objects.requireNonNull(valueResolver, "valueResolver");
        this.cryptoService = Objects.requireNonNull(cryptoService, "cryptoService");
        this.keyStatePort = Objects.requireNonNull(keyStatePort, "keyStatePort");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.transactionPort = Objects.requireNonNull(transactionPort, "transactionPort");
        this.serviceIdentity = Objects.requireNonNull(serviceIdentity, "serviceIdentity");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public FieldProjectionResult project(FieldProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision apiAuthorization;
        try {
            apiAuthorization = authorizationPort.authorize(request.authorizationRequest());
        } catch (RuntimeException unavailable) {
            return deniedAudited(
                    request, null, null, "FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE");
        }
        if (apiAuthorization.outcome() == CompositeAuthorizationOutcome.DENY) {
            return deniedAudited(request, apiAuthorization, null, "FIELD_PROJECTION_DENIED");
        }
        if (apiAuthorization.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
            return deniedAudited(
                    request, apiAuthorization, null, "FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE");
        }

        String currentFailure = currentFailure(request, apiAuthorization.decisionToken());
        if (currentFailure != null) {
            return deniedAudited(request, apiAuthorization, null, currentFailure);
        }

        CompositeAuthorizationDecision authorization;
        FieldProjectionEvidence evidence;
        Map<String, FieldProjectionValueReference> references;
        try {
            authorization = toDomain(apiAuthorization);
            evidence = toDomain(request);
            references = references(request.valueReferences());
        } catch (RuntimeException invalidEvidence) {
            return deniedAudited(
                    request, apiAuthorization, null, "FIELD_PROJECTION_EVIDENCE_INVALID");
        }
        FieldProjectionDecision projection;
        try {
            projection = evaluator.evaluate(
                    authorization, evidence, references.keySet(), catalog, rolePolicy);
        } catch (RuntimeException invalidEvidence) {
            return deniedAudited(
                    request, apiAuthorization, null, "FIELD_PROJECTION_EVIDENCE_INVALID");
        }
        if (!projection.allowed()) {
            return deniedAudited(
                    request, apiAuthorization, projection, "FIELD_PROJECTION_DENIED");
        }

        List<FieldProjectionFieldResult> visibleFields;
        try {
            visibleFields = materialize(request, projection, references);
        } catch (RuntimeException cryptoOrReferenceFailure) {
            return deniedAudited(
                    request, apiAuthorization, projection, "FIELD_PROJECTION_CRYPTO_FAILED");
        }
        FieldProjectionResult prepared = new FieldProjectionResult(true, "FIELD_PROJECTION_ALLOWED", visibleFields);
        try {
            return transactionPort.execute(() -> {
                String finalFailure = currentFailure(request, apiAuthorization.decisionToken());
                if (finalFailure != null) {
                    auditPort.record(auditRecord(
                            request, apiAuthorization, projection, auditResult(finalFailure)));
                    return FieldProjectionResult.denied(finalFailure);
                }
                auditPort.record(auditRecord(request, apiAuthorization, projection, "PROJECTED"));
                return prepared;
            });
        } catch (RuntimeException commitFailure) {
            return FieldProjectionResult.denied("FIELD_PROJECTION_AUDIT_COMMIT_FAILED");
        }
    }

    private String currentFailure(
            FieldProjectionRequest request,
            CompositeAuthorizationDecisionToken decisionToken) {
        try {
            var recheck = recheckPort.recheck(new CompositeAuthorizationRecheckRequest(
                    request.authorizationRequest(), decisionToken));
            if (recheck.outcome() == CompositeAuthorizationRecheckOutcome.STALE) {
                return "FIELD_PROJECTION_DECISION_STALE";
            }
            if (recheck.outcome() == CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE) {
                return "FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE";
            }
            if (!request.objectEvidence().keyStateVersion().equals(keyStatePort.currentStateVersion())) {
                return "FIELD_PROJECTION_KEY_STATE_STALE";
            }
            return null;
        } catch (RuntimeException unavailable) {
            return "FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE";
        }
    }

    private FieldProjectionResult deniedAudited(
            FieldProjectionRequest request,
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision authorization,
            FieldProjectionDecision projection,
            String reasonCode) {
        FieldProjectionResult denied = FieldProjectionResult.denied(reasonCode);
        try {
            return transactionPort.execute(() -> {
                auditPort.record(auditRecord(
                        request, authorization, projection, auditResult(reasonCode)));
                return denied;
            });
        } catch (RuntimeException commitFailure) {
            return FieldProjectionResult.denied("FIELD_PROJECTION_AUDIT_COMMIT_FAILED");
        }
    }

    private static String auditResult(String reasonCode) {
        return switch (reasonCode) {
            case "FIELD_PROJECTION_DENIED" -> "DENIED";
            case "FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE" -> "DEPENDENCY_UNAVAILABLE";
            case "FIELD_PROJECTION_DECISION_STALE" -> "DECISION_STALE";
            case "FIELD_PROJECTION_KEY_STATE_STALE" -> "KEY_STATE_STALE";
            case "FIELD_PROJECTION_EVIDENCE_INVALID" -> "EVIDENCE_INVALID";
            case "FIELD_PROJECTION_CRYPTO_FAILED" -> "CRYPTO_FAILED";
            default -> "DENIED";
        };
    }

    private List<FieldProjectionFieldResult> materialize(
            FieldProjectionRequest request,
            FieldProjectionDecision projection,
            Map<String, FieldProjectionValueReference> references) {
        List<FieldProjectionFieldResult> visible = new ArrayList<>();
        for (ProjectedFieldDecision field : projection.fieldDecisions()) {
            if (field.visibility() == Visibility.HIDDEN) {
                continue;
            }
            if (field.visibility() == Visibility.MASKED) {
                visible.add(new FieldProjectionFieldResult(
                        field.fieldName(), FieldVisibility.MASKED, java.util.Optional.empty(), field.maskedValue()));
                continue;
            }
            FieldProjectionValueReference reference = Objects.requireNonNull(references.get(field.fieldName()));
            ApprovedFieldDescriptor descriptor = catalog.field(field.fieldName()).orElseThrow();
            if (!reference.fieldClass().equals(descriptor.fieldClass().code())
                    || !reference.valueType().equals(descriptor.valueType())) {
                throw new IllegalArgumentException("FIELD_PROJECTION_REFERENCE_METADATA_MISMATCH");
            }
            if (reference.serverOwnedValueReference().isPresent()) {
                Object clear = Objects.requireNonNull(
                        reference.serverOwnedValueReference().orElseThrow().resolve(),
                        "FIELD_PROJECTION_SERVER_VALUE_MISSING");
                visible.add(new FieldProjectionFieldResult(
                        field.fieldName(), FieldVisibility.CLEAR,
                        java.util.Optional.of(clear), java.util.Optional.empty()));
                continue;
            }
            var encrypted = valueResolver.resolve(reference.valueReference());
            SensitiveFieldCryptoContext context = new SensitiveFieldCryptoContext(
                    request.authorizationRequest().actorPseudonym(),
                    serviceIdentity,
                    descriptor.fieldClass().name(),
                    request.objectEvidence().purpose(),
                    environment,
                    request.objectClass().name().replace("_", ""),
                    request.authorizationRequest().objectTokenDigest(),
                    field.fieldName(),
                    reference.valueReference().keyRef(),
                    reference.valueReference().keyVersion(),
                    request.authorizationRequest().traceId());
            Object clear = cryptoService.withDecryptedClearValue(
                    Visibility.CLEAR, context, encrypted, bytes -> decode(reference.valueType(), bytes)).orElseThrow();
            visible.add(new FieldProjectionFieldResult(
                    field.fieldName(), FieldVisibility.CLEAR, java.util.Optional.of(clear), java.util.Optional.empty()));
        }
        return List.copyOf(visible);
    }

    private SensitiveProjectionAuditRecord auditRecord(
            FieldProjectionRequest request,
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision authorization,
            FieldProjectionDecision projection,
            String result) {
        int clear = projection == null ? 0 : (int) projection.fieldDecisions().stream()
                .filter(field -> field.visibility() == Visibility.CLEAR).count();
        int masked = projection == null ? 0 : (int) projection.fieldDecisions().stream()
                .filter(field -> field.visibility() == Visibility.MASKED).count();
        int hidden = request.valueReferences().size() - clear - masked;
        Set<String> roles = authorization == null ? Set.of()
                : Set.copyOf(authorization.applicableRolePackages());
        return new SensitiveProjectionAuditRecord(
                request.authorizationRequest().actorPseudonym(),
                roles,
                request.authorizationRequest().actionId(),
                request.authorizationRequest().objectTokenDigest(),
                request.objectClass().name().replace("_", ""),
                sha256(request.objectEvidence().purpose()),
                authorization == null
                        ? RoleFieldPolicyCatalog.POLICY_VERSION : authorization.policyVersion(),
                FieldProjectionCatalog.VERSION,
                authorization == null
                        ? request.authorizationRequest().expectedObjectVersion()
                        : authorization.objectVersion(),
                request.objectEvidence().keyStateVersion(),
                clear,
                masked,
                hidden,
                projection == null ? Map.of()
                        : projection.fieldDecisions().stream().collect(Collectors.toMap(
                                field -> field.fieldClass().code(),
                                field -> FieldVisibility.valueOf(field.visibility().name()),
                                (left, right) -> strictest(left, right))),
                result,
                request.objectEvidence().serverNow(),
                request.authorizationRequest().traceId());
    }

    private static FieldVisibility strictest(FieldVisibility left, FieldVisibility right) {
        if (left == FieldVisibility.HIDDEN || right == FieldVisibility.HIDDEN) {
            return FieldVisibility.HIDDEN;
        }
        if (left == FieldVisibility.MASKED || right == FieldVisibility.MASKED) {
            return FieldVisibility.MASKED;
        }
        return FieldVisibility.CLEAR;
    }

    private static CompositeAuthorizationDecision toDomain(
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision decision) {
        CompositeAuthorizationDecisionToken apiToken = decision.decisionToken();
        AuthorizationEvidenceVersions versions = new AuthorizationEvidenceVersions(
                apiToken.identityVersion(), apiToken.relationVersion(), apiToken.grantVersion(),
                apiToken.invalidationVersion(), apiToken.policySequence());
        return new CompositeAuthorizationDecision(
                cn.edu.suda.scholarsense.identityaccess.domain.CompositeAuthorizationOutcome.valueOf(
                        decision.outcome().name()),
                decision.reasonCode(),
                decision.applicableRolePackages().stream()
                        .map(RolePackage::fromAuthorityId).collect(Collectors.toUnmodifiableSet()),
                decision.scopeAnchorSummary().stream()
                        .map(ScopeAnchor::valueOf).collect(Collectors.toUnmodifiableSet()),
                decision.fieldProjectionSummary().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        entry -> fieldClass(entry.getKey()),
                        entry -> Visibility.valueOf(entry.getValue().name()))),
                decision.clearConditionalFields(),
                decision.policyVersion(),
                versions,
                decision.objectVersion(),
                decision.evaluatedAt(),
                new AuthorizationDecisionToken(versions, apiToken.objectVersion(), apiToken.policyVersion()));
    }

    private static FieldProjectionEvidence toDomain(FieldProjectionRequest request) {
        var source = request.objectEvidence();
        return new FieldProjectionEvidence(
                ProjectionObjectClass.valueOf(request.objectClass().name()),
                source.purpose(),
                source.serverNow(),
                source.taskWindow().map(window -> new EffectiveWindow(window.startAt(), window.endAt())),
                source.currentWorkItem(),
                source.assigned(),
                source.ownedSource(),
                source.fieldAllowlist(),
                source.delegationFieldAllowlist());
    }

    private static Map<String, FieldProjectionValueReference> references(
            List<FieldProjectionValueReference> source) {
        LinkedHashMap<String, FieldProjectionValueReference> result = new LinkedHashMap<>();
        for (FieldProjectionValueReference reference : source) {
            if (result.putIfAbsent(reference.fieldName(), reference) != null) {
                throw new IllegalArgumentException("FIELD_PROJECTION_REFERENCE_DUPLICATE");
            }
        }
        return result;
    }

    private static FieldClass fieldClass(String code) {
        for (FieldClass fieldClass : FieldClass.values()) {
            if (fieldClass.code().equals(code)) {
                return fieldClass;
            }
        }
        throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_CLASS_UNKNOWN");
    }

    private static Object decode(String valueType, byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        return switch (valueType) {
            case "string", "timestamp" -> value;
            case "integer" -> Long.parseLong(value);
            case "boolean" -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IllegalArgumentException("FIELD_PROJECTION_BOOLEAN_INVALID");
                }
                yield Boolean.valueOf(value);
            }
            default -> throw new IllegalArgumentException("FIELD_PROJECTION_VALUE_TYPE_INVALID");
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("FIELD_PROJECTION_DIGEST_UNAVAILABLE", impossible);
        }
    }
}
