package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.List;

public record FieldProjectionResult(
        boolean allowed,
        String reasonCode,
        List<FieldProjectionFieldResult> fields) {
    public FieldProjectionResult {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_REASON_INVALID");
        }
        fields = List.copyOf(fields);
        if (!allowed && !fields.isEmpty()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_DENIED_CONTENT_INVALID");
        }
    }

    public static FieldProjectionResult denied(String reasonCode) {
        return new FieldProjectionResult(false, reasonCode, List.of());
    }
}
