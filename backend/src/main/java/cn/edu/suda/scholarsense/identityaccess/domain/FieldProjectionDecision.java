package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record FieldProjectionDecision(
        boolean allowed,
        String reasonCode,
        List<ProjectedFieldDecision> fieldDecisions) {
    public FieldProjectionDecision {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_REASON_INVALID");
        }
        fieldDecisions = List.copyOf(fieldDecisions);
        if (!allowed && !fieldDecisions.isEmpty()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_DENIED_CONTENT_INVALID");
        }
    }

    public static FieldProjectionDecision denied(String reasonCode) {
        return new FieldProjectionDecision(false, reasonCode, List.of());
    }

    public Visibility visibilityFor(String fieldName) {
        return decisionFor(fieldName)
                .map(ProjectedFieldDecision::visibility)
                .orElse(Visibility.HIDDEN);
    }

    public Optional<ProjectedFieldDecision> decisionFor(String fieldName) {
        return fieldDecisions.stream()
                .filter(item -> item.fieldName().equals(fieldName))
                .findFirst();
    }

    public Set<String> serializableFieldNames() {
        return fieldDecisions.stream()
                .filter(item -> item.visibility() != Visibility.HIDDEN)
                .map(ProjectedFieldDecision::fieldName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
