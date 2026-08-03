package cn.edu.suda.scholarsense.identityaccess.api;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One visibility-filtered representation shared by JSON and export sinks. */
public final class FieldProjectionSafeDocument {
    private final Map<String, Object> values;

    private FieldProjectionSafeDocument(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static FieldProjectionSafeDocument from(FieldProjectionResult result) {
        if (!result.allowed()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_DOCUMENT_DENIED");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (FieldProjectionFieldResult field : result.fields()) {
            Object value = switch (field.visibility()) {
                case CLEAR -> field.clearValue().orElseThrow();
                case MASKED -> field.maskedValue().orElseThrow();
                case HIDDEN -> null;
            };
            if (field.visibility() != FieldVisibility.HIDDEN && values.putIfAbsent(field.fieldName(), value) != null) {
                throw new IllegalArgumentException("FIELD_PROJECTION_DOCUMENT_FIELD_DUPLICATE");
            }
        }
        return new FieldProjectionSafeDocument(values);
    }

    public Map<String, Object> jsonValues() {
        return values;
    }

    public Map<String, Object> exportValues() {
        return values;
    }
}
