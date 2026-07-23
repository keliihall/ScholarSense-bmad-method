package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProjectedAuditRecord(Map<String, Object> fields) {
    public ProjectedAuditRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
