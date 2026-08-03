package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;
import java.util.Optional;

public record FieldProjectionFieldResult(
        String fieldName,
        FieldVisibility visibility,
        Optional<Object> clearValue,
        Optional<Object> maskedValue) {
    public FieldProjectionFieldResult {
        if (fieldName == null || !fieldName.matches("[A-Za-z][A-Za-z0-9]{1,63}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_NAME_INVALID");
        }
        Objects.requireNonNull(visibility, "visibility");
        clearValue = Objects.requireNonNull(clearValue, "clearValue");
        maskedValue = Objects.requireNonNull(maskedValue, "maskedValue");
        boolean shapeValid = switch (visibility) {
            case CLEAR -> clearValue.isPresent() && maskedValue.isEmpty();
            case MASKED -> clearValue.isEmpty() && maskedValue.isPresent();
            case HIDDEN -> clearValue.isEmpty() && maskedValue.isEmpty();
        };
        if (!shapeValid) {
            throw new IllegalArgumentException("FIELD_PROJECTION_RESULT_SHAPE_INVALID");
        }
    }
}
