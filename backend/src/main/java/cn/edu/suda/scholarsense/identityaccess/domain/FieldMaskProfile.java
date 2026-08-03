package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;

public record FieldMaskProfile(String id, Object fixedValue, String accessibleLabel) {
    public FieldMaskProfile {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_MASK_PROFILE_INVALID");
        }
        if (!(fixedValue instanceof String)
                && !(fixedValue instanceof Boolean)
                && !(fixedValue instanceof Integer)) {
            throw new IllegalArgumentException("FIELD_PROJECTION_MASK_VALUE_INVALID");
        }
        if (!"已脱敏".equals(accessibleLabel)) {
            throw new IllegalArgumentException("FIELD_PROJECTION_MASK_ACCESSIBILITY_INVALID");
        }
        Objects.requireNonNull(fixedValue, "fixedValue");
    }
}
