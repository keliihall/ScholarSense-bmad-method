package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record FieldProjectionValueReference(
        String fieldName,
        String fieldClass,
        String valueType,
        SensitiveValueReference valueReference,
        Optional<ServerOwnedFieldValueReference> serverOwnedValueReference) {
    public FieldProjectionValueReference(
            String fieldName,
            String fieldClass,
            String valueType,
            SensitiveValueReference valueReference) {
        this(fieldName, fieldClass, valueType, valueReference, Optional.empty());
    }

    public FieldProjectionValueReference {
        if (fieldName == null || !fieldName.matches("[A-Za-z][A-Za-z0-9]{1,63}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_NAME_INVALID");
        }
        if (!Set.of("B", "I", "C", "S", "E", "N", "G", "T").contains(fieldClass)) {
            throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_CLASS_INVALID");
        }
        if (!Set.of("string", "integer", "boolean", "timestamp").contains(valueType)) {
            throw new IllegalArgumentException("FIELD_PROJECTION_VALUE_TYPE_INVALID");
        }
        Objects.requireNonNull(valueReference, "valueReference");
        serverOwnedValueReference = Objects.requireNonNull(
                serverOwnedValueReference, "serverOwnedValueReference");
    }

    public static FieldProjectionValueReference serverOwned(
            String fieldName,
            String fieldClass,
            String valueType,
            ServerOwnedFieldValueReference reference) {
        Objects.requireNonNull(reference, "reference");
        return new FieldProjectionValueReference(
                fieldName,
                fieldClass,
                valueType,
                new SensitiveValueReference(
                        "SERVER_" + fieldName.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(),
                        classification(fieldClass),
                        "server-owned/value",
                        "safe-v1"),
                Optional.of(reference));
    }

    private static String classification(String fieldClass) {
        return switch (fieldClass) {
            case "B" -> "BASIC";
            case "I" -> "IDENTITY";
            case "C" -> "CONTACT";
            case "S" -> "SENSITIVE_CARE";
            case "E" -> "EVIDENCE";
            case "N" -> "NARRATIVE";
            case "G" -> "GOVERNANCE";
            case "T" -> "TECHNICAL";
            default -> throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_CLASS_INVALID");
        };
    }
}
