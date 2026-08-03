package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.Optional;

public record ApprovedFieldDescriptor(
        String name,
        FieldClass fieldClass,
        String valueType,
        Optional<FieldMaskProfile> maskProfile,
        boolean globalHidden) {
    public ApprovedFieldDescriptor {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9]{1,63}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_FIELD_NAME_INVALID");
        }
        Objects.requireNonNull(fieldClass, "fieldClass");
        if (!SetHolder.VALUE_TYPES.contains(valueType)) {
            throw new IllegalArgumentException("FIELD_PROJECTION_VALUE_TYPE_INVALID");
        }
        maskProfile = Objects.requireNonNull(maskProfile, "maskProfile");
    }

    private static final class SetHolder {
        private static final java.util.Set<String> VALUE_TYPES =
                java.util.Set.of("string", "integer", "boolean", "timestamp");

        private SetHolder() {
        }
    }
}
