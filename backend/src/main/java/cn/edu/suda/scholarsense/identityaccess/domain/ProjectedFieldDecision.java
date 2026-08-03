package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.Optional;

public record ProjectedFieldDecision(
        String fieldName,
        FieldClass fieldClass,
        Visibility visibility,
        Optional<Object> maskedValue) {
    public ProjectedFieldDecision {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_NAME_INVALID");
        }
        Objects.requireNonNull(fieldClass, "fieldClass");
        Objects.requireNonNull(visibility, "visibility");
        maskedValue = Objects.requireNonNull(maskedValue, "maskedValue");
        if ((visibility == Visibility.MASKED) != maskedValue.isPresent()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_MASK_DECISION_INVALID");
        }
    }
}
