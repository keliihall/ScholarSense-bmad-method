package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ProjectionObjectSchema(
        ProjectionObjectClass objectClass,
        Set<String> approvedPurposes,
        List<String> fieldNames) {
    public ProjectionObjectSchema {
        Objects.requireNonNull(objectClass, "objectClass");
        approvedPurposes = Set.copyOf(approvedPurposes);
        fieldNames = List.copyOf(fieldNames);
        if (objectClass == ProjectionObjectClass.UNKNOWN
                || approvedPurposes.isEmpty()
                || fieldNames.isEmpty()
                || fieldNames.size() != Set.copyOf(fieldNames).size()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_OBJECT_SCHEMA_INVALID");
        }
    }
}
