package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** FIELD-PROJECTION-POLICY-BINDING-1.1.0 field and object allowlist. */
public final class FieldProjectionCatalog {
    public static final String VERSION = "FIELD-PROJECTION-POLICY-BINDING-1.1.0";

    private final Map<String, ApprovedFieldDescriptor> globalFields;
    private final Map<ObjectFieldKey, ApprovedFieldDescriptor> scopedFields;
    private final Map<ProjectionObjectClass, ProjectionObjectSchema> objects;

    public FieldProjectionCatalog(
            List<ApprovedFieldDescriptor> fieldDescriptors,
            List<ProjectionObjectSchema> objectSchemas) {
        this(fieldDescriptors, objectSchemas, Map.of());
    }

    private FieldProjectionCatalog(
            List<ApprovedFieldDescriptor> fieldDescriptors,
            List<ProjectionObjectSchema> objectSchemas,
            Map<ProjectionObjectClass, List<ApprovedFieldDescriptor>> objectScopedDescriptors) {
        LinkedHashMap<String, ApprovedFieldDescriptor> fieldMap = new LinkedHashMap<>();
        for (ApprovedFieldDescriptor descriptor : fieldDescriptors) {
            if (fieldMap.putIfAbsent(descriptor.name(), descriptor) != null) {
                throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_DUPLICATE");
            }
        }
        LinkedHashMap<ObjectFieldKey, ApprovedFieldDescriptor> scopedMap = new LinkedHashMap<>();
        objectScopedDescriptors.forEach((objectClass, descriptors) -> descriptors.forEach(descriptor -> {
            ObjectFieldKey key = new ObjectFieldKey(objectClass, descriptor.name());
            if (scopedMap.putIfAbsent(key, descriptor) != null) {
                throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_DUPLICATE");
            }
        }));
        LinkedHashMap<ProjectionObjectClass, ProjectionObjectSchema> objectMap = new LinkedHashMap<>();
        for (ProjectionObjectSchema schema : objectSchemas) {
            if (objectMap.putIfAbsent(schema.objectClass(), schema) != null
                    || schema.fieldNames().stream().anyMatch(name ->
                            !fieldMap.containsKey(name)
                                    && !scopedMap.containsKey(new ObjectFieldKey(schema.objectClass(), name)))) {
                throw new IllegalArgumentException("FIELD_PROJECTION_OBJECT_SCHEMA_INVALID");
            }
        }
        if (scopedMap.keySet().stream().anyMatch(key -> !objectMap.containsKey(key.objectClass()))) {
            throw new IllegalArgumentException("FIELD_PROJECTION_OBJECT_SCHEMA_INVALID");
        }
        globalFields = Map.copyOf(fieldMap);
        scopedFields = Map.copyOf(scopedMap);
        objects = Map.copyOf(objectMap);
    }

    public static FieldProjectionCatalog approved() {
        Map<String, FieldMaskProfile> masks = masks();
        List<ApprovedFieldDescriptor> fields = List.of(
                f("recordId", FieldClass.BASIC, "string", null, false, masks),
                f("ledgerSequence", FieldClass.BASIC, "integer", null, false, masks),
                f("occurredAt", FieldClass.BASIC, "timestamp", "timestamp", false, masks),
                f("outcome", FieldClass.BASIC, "string", "category", false, masks),
                f("factSchemaVersion", FieldClass.BASIC, "string", "code", false, masks),
                f("policyVersion", FieldClass.BASIC, "string", "code", false, masks),
                f("retentionScheduleVersion", FieldClass.BASIC, "string", "code", false, masks),
                f("actorDisplayRef", FieldClass.IDENTITY, "string", "identity", false, masks),
                f("objectDisplayRef", FieldClass.IDENTITY, "string", "identity", false, masks),
                f("businessActionCategory", FieldClass.GOVERNANCE, "string", "category", false, masks),
                f("businessObjectCategory", FieldClass.GOVERNANCE, "string", "category", false, masks),
                f("rolePackageSummary", FieldClass.GOVERNANCE, "string", "category", false, masks),
                f("projectionScope", FieldClass.GOVERNANCE, "string", "category", false, masks),
                f("producerModule", FieldClass.TECHNICAL, "string", "technical", false, masks),
                f("eventType", FieldClass.TECHNICAL, "string", "technical", false, masks),
                f("reasonCode", FieldClass.TECHNICAL, "string", "code", false, masks),
                f("traceId", FieldClass.TECHNICAL, "string", "technical", false, masks),
                f("integrityStatus", FieldClass.TECHNICAL, "string", "technical", false, masks),
                f("archiveStatus", FieldClass.TECHNICAL, "string", "technical", false, masks),
                f("projectionStatus", FieldClass.TECHNICAL, "string", "technical", false, masks),
                f("sourceNetworkRecorded", FieldClass.TECHNICAL, "boolean", "boolean", false, masks),
                f("transferOrderId", FieldClass.BASIC, "string", null, false, masks),
                f("status", FieldClass.BASIC, "string", "category", false, masks),
                f("startAt", FieldClass.BASIC, "timestamp", "timestamp", false, masks),
                f("endAt", FieldClass.BASIC, "timestamp", "timestamp", false, masks),
                f("studentDisplayRef", FieldClass.IDENTITY, "string", "identity", false, masks),
                f("studentContactPhone", FieldClass.CONTACT, "string", "identity", false, masks),
                f("studentContactEmail", FieldClass.CONTACT, "string", "identity", false, masks),
                f("referralReasonCode", FieldClass.SENSITIVE_CARE, "string", "code", false, masks),
                f("requestedServiceCode", FieldClass.SENSITIVE_CARE, "string", "code", false, masks),
                f("urgencyLevel", FieldClass.EVIDENCE, "string", "category", false, masks),
                f("referralSummary", FieldClass.NARRATIVE, "string", "narrative", false, masks),
                f("supplementRequestText", FieldClass.NARRATIVE, "string", "narrative", false, masks),
                f("resultSummary", FieldClass.NARRATIVE, "string", "narrative", false, masks),
                f("departmentCategory", FieldClass.GOVERNANCE, "string", "category", false, masks),
                f("objectVersion", FieldClass.TECHNICAL, "string", "code", false, masks),
                f("transferPolicyVersion", FieldClass.TECHNICAL, "string", "code", false, masks),
                f("keyVersion", FieldClass.TECHNICAL, "string", "code", false, masks),
                f("exceptionId", FieldClass.BASIC, "string", null, false, masks),
                f("subjectOfficialRef", FieldClass.IDENTITY, "string", "identity", false, masks),
                f("exceptionCode", FieldClass.EVIDENCE, "string", "code", false, masks),
                f("sourceSystem", FieldClass.EVIDENCE, "string", "technical", false, masks),
                f("sourceOwner", FieldClass.GOVERNANCE, "string", "category", false, masks),
                f("detectedAt", FieldClass.TECHNICAL, "timestamp", "timestamp", false, masks),
                f("diagnosisText", FieldClass.NARRATIVE, "string", null, true, masks),
                f("counselingText", FieldClass.NARRATIVE, "string", null, true, masks),
                f("evidenceBody", FieldClass.NARRATIVE, "string", null, true, masks),
                f("networkContent", FieldClass.TECHNICAL, "string", null, true, masks),
                f("thirdPartyContact", FieldClass.CONTACT, "string", null, true, masks),
                f("keyValue", FieldClass.TECHNICAL, "string", null, true, masks));
        List<ApprovedFieldDescriptor> qualitySnapshotFields = List.of(
                f("snapshotId", FieldClass.BASIC, "string", null, false, masks),
                f("batchId", FieldClass.BASIC, "string", null, false, masks),
                f("sourceId", FieldClass.BASIC, "string", null, false, masks),
                f("assessedBatchStatus", FieldClass.BASIC, "string", null, false, masks),
                f("overallResult", FieldClass.BASIC, "string", null, false, masks),
                f("observationWindow.startAt", FieldClass.BASIC, "timestamp", null, false, masks),
                f("observationWindow.endAt", FieldClass.BASIC, "timestamp", null, false, masks),
                f("cutoffAt", FieldClass.BASIC, "timestamp", null, false, masks),
                f("evaluatedAt", FieldClass.BASIC, "timestamp", null, false, masks),
                f("watermark", FieldClass.BASIC, "string", null, false, masks),
                f("metricResults[].metricId", FieldClass.BASIC, "string", null, false, masks),
                f("metricResults[].result", FieldClass.BASIC, "string", null, false, masks),
                f("metricResults[].applicable", FieldClass.BASIC, "boolean", null, false, masks),
                f("metricResults[].numerator", FieldClass.BASIC, "integer", null, false, masks),
                f("metricResults[].denominator", FieldClass.BASIC, "integer", null, false, masks),
                f("metricResults[].valueBasisPoints", FieldClass.BASIC, "integer", null, false, masks),
                f("metricResults[].unit", FieldClass.BASIC, "string", null, false, masks),
                f("metricResults[].operator", FieldClass.BASIC, "string", null, false, masks),
                f("metricResults[].thresholdNumerator", FieldClass.BASIC, "integer", null, false, masks),
                f("metricResults[].thresholdDenominator", FieldClass.BASIC, "integer", null, false, masks),
                f("metricResults[].boundary", FieldClass.BASIC, "string", null, false, masks),
                f("metricResults[].reasonCode", FieldClass.EVIDENCE, "string", null, false, masks),
                f("impactScopeCodes[]", FieldClass.EVIDENCE, "string", null, false, masks),
                f("sourceOwnerRef", FieldClass.GOVERNANCE, "string", null, false, masks),
                f("approvalRef", FieldClass.GOVERNANCE, "string", null, false, masks),
                f("effectiveAt", FieldClass.GOVERNANCE, "timestamp", null, false, masks),
                f("retentionScheduleVersion", FieldClass.GOVERNANCE, "string", null, false, masks),
                f("qualityMetricDecisionProfileVersion", FieldClass.TECHNICAL, "string", null, false, masks),
                f("qualityMetricDecisionProfileDigest", FieldClass.TECHNICAL, "string", null, false, masks),
                f("qualityGateVersion", FieldClass.TECHNICAL, "string", null, false, masks),
                f("qualityGateDigest", FieldClass.TECHNICAL, "string", null, false, masks),
                f("metricResults[].formulaId", FieldClass.TECHNICAL, "string", null, false, masks),
                f("metricResults[].formulaVersion", FieldClass.TECHNICAL, "string", null, false, masks),
                f("canonicalizationProfile", FieldClass.TECHNICAL, "string", null, false, masks),
                f("manifestDigest", FieldClass.TECHNICAL, "string", null, false, masks),
                f("sourceSchemaVersion", FieldClass.TECHNICAL, "string", null, false, masks),
                f("sourceSchemaDigest", FieldClass.TECHNICAL, "string", null, false, masks),
                f("immutableHash", FieldClass.TECHNICAL, "string", null, false, masks),
                f("traceId", FieldClass.TECHNICAL, "string", null, false, masks),
                f("lineageId", FieldClass.TECHNICAL, "string", null, false, masks),
                f("supersedesSnapshotId", FieldClass.TECHNICAL, "string", null, false, masks),
                f("aggregateVersion", FieldClass.TECHNICAL, "integer", null, false, masks));
        List<ProjectionObjectSchema> objectSchemas = List.of(
                new ProjectionObjectSchema(
                        ProjectionObjectClass.AUDIT_SEARCH_RECORD,
                        Set.of("audit.search-business-metadata", "audit.search-technical-metadata"),
                        List.of(
                                "recordId", "ledgerSequence", "occurredAt", "outcome",
                                "factSchemaVersion", "policyVersion", "retentionScheduleVersion",
                                "actorDisplayRef", "objectDisplayRef", "businessActionCategory",
                                "businessObjectCategory", "rolePackageSummary", "projectionScope",
                                "producerModule", "eventType", "reasonCode", "traceId",
                                "integrityStatus", "archiveStatus", "projectionStatus",
                                "sourceNetworkRecorded", "evidenceBody", "networkContent", "keyValue")),
                new ProjectionObjectSchema(
                        ProjectionObjectClass.SUBJECT_MAPPING_EXCEPTION,
                        Set.of("subject-mapping-repair"),
                        List.of(
                                "exceptionId", "status", "subjectOfficialRef", "exceptionCode",
                                "sourceSystem", "sourceOwner", "detectedAt", "evidenceBody", "keyValue")),
                new ProjectionObjectSchema(
                        ProjectionObjectClass.TRANSFER_ORDER,
                        Set.of(
                                "transfer.read", "transfer.accept", "transfer.process",
                                "transfer.request-supplement", "transfer.fill-result", "transfer.close"),
                        List.of(
                                "transferOrderId", "status", "startAt", "endAt", "studentDisplayRef",
                                "studentContactPhone", "studentContactEmail", "referralReasonCode",
                                "requestedServiceCode", "urgencyLevel", "referralSummary",
                                "supplementRequestText", "resultSummary", "departmentCategory",
                                "objectVersion", "transferPolicyVersion", "keyVersion", "diagnosisText",
                                "counselingText", "evidenceBody", "networkContent", "thirdPartyContact",
                                "keyValue")),
                new ProjectionObjectSchema(
                        ProjectionObjectClass.QUALITY_SNAPSHOT,
                        Set.of("data-quality.read"),
                        qualitySnapshotFields.stream().map(ApprovedFieldDescriptor::name).toList()));
        return new FieldProjectionCatalog(
                fields,
                objectSchemas,
                Map.of(ProjectionObjectClass.QUALITY_SNAPSHOT, qualitySnapshotFields));
    }

    public Optional<ApprovedFieldDescriptor> field(String name) {
        return Optional.ofNullable(globalFields.get(name));
    }

    public Optional<ApprovedFieldDescriptor> field(
            ProjectionObjectClass objectClass, String path) {
        ProjectionObjectSchema schema = objects.get(objectClass);
        if (schema == null || !schema.fieldNames().contains(path)) {
            return Optional.empty();
        }
        ApprovedFieldDescriptor scoped = scopedFields.get(new ObjectFieldKey(objectClass, path));
        return Optional.ofNullable(scoped == null ? globalFields.get(path) : scoped);
    }

    public Optional<ProjectionObjectSchema> objectSchema(ProjectionObjectClass objectClass) {
        return Optional.ofNullable(objects.get(objectClass));
    }

    private static Map<String, FieldMaskProfile> masks() {
        return Map.of(
                "boolean", new FieldMaskProfile("boolean", false, "已脱敏"),
                "category", new FieldMaskProfile("category", "[MASKED-CATEGORY]", "已脱敏"),
                "code", new FieldMaskProfile("code", "[MASKED-CODE]", "已脱敏"),
                "identity", new FieldMaskProfile("identity", "[MASKED-IDENTITY]", "已脱敏"),
                "narrative", new FieldMaskProfile("narrative", "[MASKED-NARRATIVE]", "已脱敏"),
                "technical", new FieldMaskProfile("technical", "[MASKED-TECHNICAL]", "已脱敏"),
                "timestamp", new FieldMaskProfile("timestamp", "[MASKED-TIME]", "已脱敏"));
    }

    private static ApprovedFieldDescriptor f(
            String name,
            FieldClass fieldClass,
            String valueType,
            String maskProfile,
            boolean globalHidden,
            Map<String, FieldMaskProfile> masks) {
        return new ApprovedFieldDescriptor(
                name,
                fieldClass,
                valueType,
                Optional.ofNullable(maskProfile).map(masks::get),
                globalHidden);
    }

    private record ObjectFieldKey(
            ProjectionObjectClass objectClass, String path) {
        private ObjectFieldKey {
            java.util.Objects.requireNonNull(objectClass, "objectClass");
            java.util.Objects.requireNonNull(path, "path");
        }
    }
}
