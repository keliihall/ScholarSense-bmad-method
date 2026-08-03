package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure C/M/H projector. It performs no value reads, decryption, serialization, or I/O. */
public final class FieldProjectionEvaluator {
    private static final Set<String> R5_CLOSED_FIELD_UNIVERSE = Set.of(
            "studentContactPhone",
            "studentContactEmail",
            "referralReasonCode",
            "requestedServiceCode",
            "referralSummary",
            "supplementRequestText",
            "resultSummary");

    public FieldProjectionDecision evaluate(
            CompositeAuthorizationDecision authorization,
            FieldProjectionEvidence evidence,
            Set<String> requestedFields,
            FieldProjectionCatalog catalog,
            RoleFieldPolicyCatalog rolePolicy) {
        if (authorization.outcome() != CompositeAuthorizationOutcome.ALLOW) {
            return FieldProjectionDecision.denied("AUTHORIZATION_NOT_ALLOWED");
        }
        if (!rolePolicy.available()) {
            return FieldProjectionDecision.denied("POLICY_UNAVAILABLE");
        }
        Optional<ProjectionObjectSchema> maybeSchema = catalog.objectSchema(evidence.objectClass());
        if (maybeSchema.isEmpty()) {
            return FieldProjectionDecision.denied("PROJECTION_OBJECT_UNKNOWN");
        }
        ProjectionObjectSchema schema = maybeSchema.orElseThrow();
        if (evidence.purpose() == null || !schema.approvedPurposes().contains(evidence.purpose())) {
            return FieldProjectionDecision.denied("PURPOSE_NOT_APPROVED");
        }
        Set<RolePackage> roles = authorization.applicableRolePackages();
        if (roles.isEmpty()) {
            return FieldProjectionDecision.denied("ROLE_NOT_APPLICABLE");
        }
        if (roles.contains(RolePackage.R2) && !evidence.currentWorkItem()) {
            return FieldProjectionDecision.denied("CURRENT_WORK_ITEM_REQUIRED");
        }
        if (roles.contains(RolePackage.R5) && !activeTransferEvidence(evidence)) {
            return FieldProjectionDecision.denied("TASK_WINDOW_INACTIVE");
        }

        Map<FieldClass, Visibility> strictRoleVisibility = strictRoleVisibility(roles, rolePolicy);
        List<ProjectedFieldDecision> decisions = new ArrayList<>();
        for (String fieldName : schema.fieldNames()) {
            if (!requestedFields.contains(fieldName)) {
                continue;
            }
            ApprovedFieldDescriptor descriptor = catalog.field(fieldName).orElse(null);
            if (descriptor == null) {
                continue;
            }
            Visibility visibility = decide(
                    descriptor,
                    authorization,
                    evidence,
                    roles,
                    strictRoleVisibility);
            Optional<Object> maskedValue = Optional.empty();
            if (visibility == Visibility.MASKED) {
                maskedValue = descriptor.maskProfile().map(FieldMaskProfile::fixedValue);
                if (maskedValue.isEmpty()) {
                    visibility = Visibility.HIDDEN;
                }
            }
            decisions.add(new ProjectedFieldDecision(
                    descriptor.name(), descriptor.fieldClass(), visibility, maskedValue));
        }
        return new FieldProjectionDecision(true, "PROJECTED", decisions);
    }

    private static Visibility decide(
            ApprovedFieldDescriptor descriptor,
            CompositeAuthorizationDecision authorization,
            FieldProjectionEvidence evidence,
            Set<RolePackage> roles,
            Map<FieldClass, Visibility> strictRoleVisibility) {
        if (descriptor.globalHidden()) {
            return Visibility.HIDDEN;
        }
        Visibility visibility = strictRoleVisibility.getOrDefault(
                descriptor.fieldClass(), Visibility.HIDDEN);
        visibility = Visibility.strictest(
                visibility,
                authorization.fieldProjectionSummary().getOrDefault(
                        descriptor.fieldClass(), Visibility.HIDDEN));

        if (roles.contains(RolePackage.R5)
                && Set.of(FieldClass.CONTACT, FieldClass.SENSITIVE_CARE, FieldClass.NARRATIVE)
                        .contains(descriptor.fieldClass())) {
            if (!R5_CLOSED_FIELD_UNIVERSE.contains(descriptor.name())
                    || !evidence.fieldAllowlist().contains(descriptor.name())
                    || !authorization.clearConditionalFields().contains(descriptor.name())
                    || evidence.delegationFieldAllowlist()
                            .map(fields -> !fields.contains(descriptor.name()))
                            .orElse(false)) {
                return Visibility.HIDDEN;
            }
        }
        if (roles.equals(Set.of(RolePackage.R6))
                && descriptor.fieldClass() == FieldClass.IDENTITY
                && evidence.objectClass() == ProjectionObjectClass.SUBJECT_MAPPING_EXCEPTION
                && "subject-mapping-repair".equals(evidence.purpose())
                && evidence.ownedSource()
                && authorization.clearConditionalFields().contains(descriptor.name())) {
            return Visibility.CLEAR;
        }
        return visibility;
    }

    private static boolean activeTransferEvidence(FieldProjectionEvidence evidence) {
        return evidence.objectClass() == ProjectionObjectClass.TRANSFER_ORDER
                && evidence.assigned()
                && evidence.taskWindow()
                        .map(window -> window.contains(evidence.serverNow()))
                        .orElse(false);
    }

    private static Map<FieldClass, Visibility> strictRoleVisibility(
            Set<RolePackage> roles, RoleFieldPolicyCatalog policy) {
        EnumMap<FieldClass, Visibility> result = new EnumMap<>(FieldClass.class);
        for (RolePackage role : roles) {
            for (Map.Entry<FieldClass, Visibility> entry : policy.fieldVisibility(role).entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), Visibility::strictest);
            }
        }
        return result;
    }
}
