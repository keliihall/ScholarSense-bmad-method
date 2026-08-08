package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RoleFieldPolicyCatalog {
    public static final String POLICY_VERSION = "RFP-1.0.0";

    private static final List<RolePackage> SURFACE_ORDER = List.of(
            RolePackage.R1,
            RolePackage.R5,
            RolePackage.R3,
            RolePackage.R6,
            RolePackage.R2,
            RolePackage.R4,
            RolePackage.R7);
    private static final Map<RolePackage, String> SURFACES = Map.of(
            RolePackage.R1, "care-workbench",
            RolePackage.R2, "college-governance",
            RolePackage.R3, "rule-operations-governance",
            RolePackage.R4, "school-dashboard",
            RolePackage.R5, "collaboration-orders",
            RolePackage.R6, "data-quality",
            RolePackage.R7, "technical-operations");

    private final boolean available;
    private final String unavailableReason;
    private final Map<RolePackage, RoleRule> roleRules;
    private final Set<String> knownActions;

    private RoleFieldPolicyCatalog(boolean available, String unavailableReason) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.roleRules = approvedRoleRules();
        this.knownActions = roleRules.values().stream()
                .flatMap(rule -> rule.actionsByObject().values().stream())
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static RoleFieldPolicyCatalog approved() {
        return new RoleFieldPolicyCatalog(true, null);
    }

    public static RoleFieldPolicyCatalog unavailable(String reasonCode) {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_POLICY_REASON_INVALID");
        }
        return new RoleFieldPolicyCatalog(false, reasonCode);
    }

    public boolean available() {
        return available;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    public boolean knownAction(ActionId actionId) {
        return knownActions.contains(actionId.value());
    }

    public boolean matches(RolePackage role, ObjectClass objectClass, ActionId actionId) {
        RoleRule rule = roleRules.get(role);
        return rule != null
                && rule.actionsByObject().getOrDefault(objectClass, Set.of()).contains(actionId.value());
    }

    public Set<ScopeAnchor> matchingAnchors(
            RolePackage role, ObjectClass objectClass, Set<ScopeAnchor> evidence) {
        RoleRule rule = roleRules.get(role);
        if (rule == null) {
            return Set.of();
        }
        Set<ScopeAnchor> allowed = rule.anchorsByObject().getOrDefault(objectClass, Set.of());
        EnumSet<ScopeAnchor> result = EnumSet.noneOf(ScopeAnchor.class);
        result.addAll(allowed);
        result.retainAll(evidence);
        return Set.copyOf(result);
    }

    public Map<FieldClass, Visibility> fieldVisibility(RolePackage role) {
        return roleRules.get(role).fieldVisibility();
    }

    public Set<String> conditionalClearFields(
            RolePackage role, ObjectClass objectClass, String purpose, Set<String> requested) {
        if (role == RolePackage.R5 && objectClass == ObjectClass.TRANSFER_ORDER) {
            Set<String> approved = Set.of(
                    "studentContactPhone",
                    "studentContactEmail",
                    "referralReasonCode",
                    "requestedServiceCode",
                    "referralSummary",
                    "supplementRequestText",
                    "resultSummary");
            return requested.stream().filter(approved::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (role == RolePackage.R6
                && objectClass == ObjectClass.SUBJECT_MAPPING_EXCEPTION
                && "SUBJECT_MAPPING_EXCEPTION_REPAIR".equals(purpose)) {
            return requested;
        }
        return Set.of();
    }

    public Optional<String> requiredHrapAction(ObjectClass objectClass, ActionId actionId) {
        String action = actionId.value();
        if ("governance.publish".equals(action)) {
            return objectClass == ObjectClass.RULE
                    ? Optional.of("rule.publish")
                    : objectClass == ObjectClass.STRATEGY
                            ? Optional.of("strategy.publish")
                            : Optional.empty();
        }
        if ("governance.rollback".equals(action)) {
            return objectClass == ObjectClass.RULE
                    ? Optional.of("rule.rollback")
                    : objectClass == ObjectClass.STRATEGY
                            ? Optional.of("strategy.rollback")
                            : Optional.empty();
        }
        return Optional.ofNullable(Map.ofEntries(
                Map.entry("delegation.issue", "temporary-grant.issue"),
                Map.entry("delegation.revoke", "temporary-grant.revoke"),
                Map.entry("whitelist.change", "whitelist.create-or-change"),
                Map.entry("bulk.execute", "bulk-governance.execute"),
                Map.entry("governance-action.record", "leader-action.record"),
                Map.entry("sensitive-export.create", "sensitive-export.create"),
                Map.entry("sensitive-export.download", "sensitive-export.download"),
                Map.entry("quality-fuse.recover", "quality-fuse.recover"),
                Map.entry("transfer.submit", "transfer.submit"))
                .get(action));
    }

    public Optional<String> explicitDenyReason(
            Set<RolePackage> roles, ObjectClass objectClass, ActionId actionId,
            Set<ScopeAnchor> anchors) {
        if (roles.contains(RolePackage.R2)
                && isIndividualCareObject(objectClass)
                && "care.read".equals(actionId.value())
                && !anchors.contains(ScopeAnchor.GOVERNANCE_WORK_ITEM)) {
            return Optional.of("EXPLICIT_WORKITEM_REQUIRED");
        }
        if (roles.contains(RolePackage.R4) && isIndividualObject(objectClass)) {
            return Optional.of("EXPLICIT_INDIVIDUAL_DRILLDOWN_DENIED");
        }
        if (roles.contains(RolePackage.R7) && isBusinessObject(objectClass)) {
            return Optional.of("EXPLICIT_BUSINESS_OBJECT_DENIED");
        }
        return Optional.empty();
    }

    public String selectDefaultSurface(Set<RolePackage> roles) {
        for (RolePackage role : SURFACE_ORDER) {
            if (roles.contains(role)) {
                return SURFACES.get(role);
            }
        }
        throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ROLE_REQUIRED");
    }

    private static boolean isIndividualCareObject(ObjectClass objectClass) {
        return Set.of(ObjectClass.CANDIDATE, ObjectClass.CLUE, ObjectClass.CARE_ACTION)
                .contains(objectClass);
    }

    private static boolean isIndividualObject(ObjectClass objectClass) {
        return Set.of(
                ObjectClass.CANDIDATE,
                ObjectClass.CLUE,
                ObjectClass.CARE_ACTION,
                ObjectClass.OBSERVATION,
                ObjectClass.TASK,
                ObjectClass.TRANSFER_ORDER).contains(objectClass);
    }

    private static boolean isBusinessObject(ObjectClass objectClass) {
        return !Set.of(
                ObjectClass.RUNTIME,
                ObjectClass.JOB,
                ObjectClass.DELIVERY,
                ObjectClass.TELEMETRY,
                ObjectClass.CONFIG,
                ObjectClass.UNKNOWN).contains(objectClass);
    }

    private static Map<RolePackage, RoleRule> approvedRoleRules() {
        Map<RolePackage, RoleRule> result = new EnumMap<>(RolePackage.class);
        result.put(RolePackage.R1, roleRule(
                actions(
                        pair(ObjectClass.CANDIDATE, "care.read", "candidate.review"),
                        pair(ObjectClass.CLUE, "care.read", "clue.follow-up"),
                        pair(ObjectClass.CARE_ACTION, "care.read"),
                        pair(ObjectClass.OBSERVATION, "care.read", "observation.manage"),
                        pair(ObjectClass.TASK, "care.read"),
                        pair(ObjectClass.TRANSFER_ORDER, "transfer.submit", "transfer.read", "transfer.resubmit"),
                        pair(ObjectClass.DELEGATION_GRANT, "delegation.issue", "delegation.revoke")),
                anchors(
                        anchor(Set.of(ObjectClass.CANDIDATE, ObjectClass.CLUE, ObjectClass.CARE_ACTION, ObjectClass.OBSERVATION, ObjectClass.TASK), ScopeAnchor.CURRENT_RESPONSIBILITY, ScopeAnchor.VALID_DELEGATION_GRANT),
                        anchor(Set.of(ObjectClass.TRANSFER_ORDER), ScopeAnchor.INITIATOR),
                        anchor(Set.of(ObjectClass.DELEGATION_GRANT), ScopeAnchor.CURRENT_RESPONSIBILITY)),
                fields("C", "C", "C", "C", "C", "C", "C", "M")));
        result.put(RolePackage.R2, roleRule(
                actions(
                        pair(ObjectClass.AGGREGATE_REPORT, "aggregate.read", "aggregate.export"),
                        pair(ObjectClass.GOVERNANCE, "governance-action.record", "governance-action.manage"),
                        pair(ObjectClass.CANDIDATE, "care.read"),
                        pair(ObjectClass.CLUE, "care.read"),
                        pair(ObjectClass.CARE_ACTION, "care.read")),
                anchors(
                        anchor(Set.of(ObjectClass.AGGREGATE_REPORT, ObjectClass.GOVERNANCE), ScopeAnchor.COLLEGE_AGGREGATE),
                        anchor(Set.of(ObjectClass.CANDIDATE, ObjectClass.CLUE, ObjectClass.CARE_ACTION), ScopeAnchor.GOVERNANCE_WORK_ITEM)),
                fields("C", "M", "H", "H", "M", "H", "C", "M")));
        result.put(RolePackage.R3, roleRule(
                actions(
                        pair(ObjectClass.RULE, "governance.read", "governance.edit", "governance.review", "governance.publish", "governance.rollback"),
                        pair(ObjectClass.STRATEGY, "governance.read", "governance.edit", "governance.review", "governance.publish", "governance.rollback"),
                        pair(ObjectClass.TAG, "governance.read", "whitelist.change"),
                        pair(ObjectClass.PROGRAM, "governance.read", "bulk.execute"),
                        pair(ObjectClass.GOVERNANCE, "governance.read", "governance-action.record", "governance-action.manage"),
                        pair(ObjectClass.AGGREGATE_REPORT, "aggregate.read", "aggregate.export", "audit.search-business-metadata", "sensitive-export.create", "sensitive-export.download"),
                        pair(ObjectClass.CANDIDATE, "care.read"),
                        pair(ObjectClass.CLUE, "care.read"),
                        pair(ObjectClass.CARE_ACTION, "care.read")),
                anchors(
                        anchor(Set.of(ObjectClass.RULE, ObjectClass.TAG, ObjectClass.PROGRAM, ObjectClass.GOVERNANCE, ObjectClass.STRATEGY, ObjectClass.AGGREGATE_REPORT), ScopeAnchor.SCHOOL_GOVERNANCE),
                        anchor(Set.of(ObjectClass.CANDIDATE, ObjectClass.CLUE, ObjectClass.CARE_ACTION), ScopeAnchor.GOVERNANCE_WORK_ITEM)),
                fields("C", "M", "H", "M", "M", "H", "C", "M")));
        result.put(RolePackage.R4, roleRule(
                actions(
                        pair(ObjectClass.AGGREGATE_REPORT, "aggregate.read", "aggregate.export"),
                        pair(ObjectClass.GOVERNANCE, "governance-action.record", "governance-action.manage")),
                anchors(anchor(Set.of(ObjectClass.AGGREGATE_REPORT, ObjectClass.GOVERNANCE), ScopeAnchor.SCHOOL_AGGREGATE, ScopeAnchor.COLLEGE_AGGREGATE)),
                fields("C", "H", "H", "H", "H", "H", "C", "M")));
        result.put(RolePackage.R5, roleRule(
                actions(pair(ObjectClass.TRANSFER_ORDER, "care.read", "transfer.read", "transfer.accept", "transfer.process", "transfer.request-supplement", "transfer.fill-result", "transfer.close")),
                anchors(anchor(Set.of(ObjectClass.TRANSFER_ORDER), ScopeAnchor.CURRENT_TRANSFER_ASSIGNMENT)),
                fields("C", "C", "C", "C", "M", "C", "H", "M")));
        result.put(RolePackage.R6, roleRule(
                actions(
                        pair(ObjectClass.SOURCE, "data-quality.read", "data-quality.repair", "data-quality.reconcile", "platform.read-source", "sensitive-export.create", "sensitive-export.download"),
                        pair(ObjectClass.DEPENDENCY, "data-quality.read", "data-quality.reconcile"),
                        pair(ObjectClass.QUALITY_SNAPSHOT, "data-quality.read"),
                        pair(ObjectClass.RECOVERY_TASK, "data-quality.read", "quality-fuse.recover"),
                        pair(ObjectClass.JOB, "data-quality.read"),
                        pair(ObjectClass.SUBJECT_MAPPING_EXCEPTION, "data-quality.read", "data-quality.repair")),
                anchors(anchor(Set.of(ObjectClass.SOURCE, ObjectClass.DEPENDENCY, ObjectClass.QUALITY_SNAPSHOT, ObjectClass.RECOVERY_TASK, ObjectClass.JOB, ObjectClass.SUBJECT_MAPPING_EXCEPTION), ScopeAnchor.OWNED_SOURCE)),
                fields("C", "M", "H", "H", "C", "H", "C", "C")));
        result.put(RolePackage.R7, roleRule(
                actions(
                        pair(ObjectClass.RUNTIME, "platform.read", "platform.diagnose", "platform.reconcile"),
                        pair(ObjectClass.JOB, "platform.read", "platform.diagnose", "platform.retry", "platform.reconcile"),
                        pair(ObjectClass.DELIVERY, "platform.read", "platform.diagnose", "platform.retry", "platform.reconcile"),
                        pair(ObjectClass.TELEMETRY, "platform.read", "platform.diagnose", "audit.search-technical-metadata"),
                        pair(ObjectClass.CONFIG, "platform.read", "platform.diagnose", "role-binding.apply-approved")),
                anchors(anchor(Set.of(ObjectClass.RUNTIME, ObjectClass.JOB, ObjectClass.DELIVERY, ObjectClass.TELEMETRY, ObjectClass.CONFIG), ScopeAnchor.TECHNICAL_OBJECT)),
                fields("C", "H", "H", "H", "H", "H", "M", "C")));
        return Map.copyOf(result);
    }

    private static RoleRule roleRule(
            Map<ObjectClass, Set<String>> actions,
            Map<ObjectClass, Set<ScopeAnchor>> anchors,
            Map<FieldClass, Visibility> fields) {
        return new RoleRule(actions, anchors, fields);
    }

    @SafeVarargs
    private static Map<ObjectClass, Set<String>> actions(Map.Entry<ObjectClass, Set<String>>... entries) {
        Map<ObjectClass, Set<String>> result = new EnumMap<>(ObjectClass.class);
        for (Map.Entry<ObjectClass, Set<String>> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static Map.Entry<ObjectClass, Set<String>> pair(ObjectClass objectClass, String... actions) {
        return Map.entry(objectClass, Set.of(actions));
    }

    @SafeVarargs
    private static Map<ObjectClass, Set<ScopeAnchor>> anchors(Map<ObjectClass, Set<ScopeAnchor>>... groups) {
        Map<ObjectClass, Set<ScopeAnchor>> result = new EnumMap<>(ObjectClass.class);
        for (Map<ObjectClass, Set<ScopeAnchor>> group : groups) {
            result.putAll(group);
        }
        return Map.copyOf(result);
    }

    private static Map<ObjectClass, Set<ScopeAnchor>> anchor(
            Set<ObjectClass> objectClasses, ScopeAnchor... anchors) {
        Map<ObjectClass, Set<ScopeAnchor>> result = new EnumMap<>(ObjectClass.class);
        for (ObjectClass objectClass : objectClasses) {
            result.put(objectClass, Set.of(anchors));
        }
        return result;
    }

    private static Map<FieldClass, Visibility> fields(String... values) {
        if (values.length != FieldClass.values().length) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_FIELD_MATRIX_INVALID");
        }
        Map<FieldClass, Visibility> result = new LinkedHashMap<>();
        FieldClass[] classes = FieldClass.values();
        for (int index = 0; index < classes.length; index++) {
            result.put(classes[index], switch (values[index]) {
                case "C" -> Visibility.CLEAR;
                case "M" -> Visibility.MASKED;
                case "H" -> Visibility.HIDDEN;
                default -> throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_VISIBILITY_INVALID");
            });
        }
        return Map.copyOf(result);
    }

    private record RoleRule(
            Map<ObjectClass, Set<String>> actionsByObject,
            Map<ObjectClass, Set<ScopeAnchor>> anchorsByObject,
            Map<FieldClass, Visibility> fieldVisibility) {
        private RoleRule {
            actionsByObject = Map.copyOf(actionsByObject);
            anchorsByObject = Map.copyOf(anchorsByObject);
            fieldVisibility = Map.copyOf(fieldVisibility);
        }
    }
}
