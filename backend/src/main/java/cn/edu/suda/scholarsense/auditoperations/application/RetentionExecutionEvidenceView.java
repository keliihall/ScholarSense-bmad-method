package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Map;

public record RetentionExecutionEvidenceView(Map<String, Object> fields) {
    public RetentionExecutionEvidenceView {
        fields = Map.copyOf(fields);
    }
}
