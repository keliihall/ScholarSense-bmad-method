package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** FIELD-PROJECTION-POLICY-BINDING-1.0.0 field and object allowlist. */
public final class FieldProjectionCatalog {
    public static final String VERSION = "FIELD-PROJECTION-POLICY-BINDING-1.0.0";

    private final Map<String, ApprovedFieldDescriptor> fields;
    private final Map<ProjectionObjectClass, ProjectionObjectSchema> objects;

    public FieldProjectionCatalog(
            List<ApprovedFieldDescriptor> fieldDescriptors,
            List<ProjectionObjectSchema> objectSchemas) {
        LinkedHashMap<String, ApprovedFieldDescriptor> fieldMap = new LinkedHashMap<>();
        for (ApprovedFieldDescriptor descriptor : fieldDescriptors) {
            if (fieldMap.putIfAbsent(descriptor.name(), descriptor) != null) {
                throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_DUPLICATE");
            }
        }
        LinkedHashMap<ProjectionObjectClass, ProjectionObjectSchema> objectMap = new LinkedHashMap<>();
        for (ProjectionObjectSchema schema : objectSchemas) {
            if (objectMap.putIfAbsent(schema.objectClass(), schema) != null
                    || !fieldMap.keySet().containsAll(schema.fieldNames())) {
                throw new IllegalArgumentException("FIELD_PROJECTION_OBJECT_SCHEMA_INVALID");
            }
        }
        fields = Map.copyOf(fieldMap);
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
        return new FieldProjectionCatalog(fields, List.of(
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
                                "keyValue"))));
    }

    public Optional<ApprovedFieldDescriptor> field(String name) {
        return Optional.ofNullable(fields.get(name));
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
}
