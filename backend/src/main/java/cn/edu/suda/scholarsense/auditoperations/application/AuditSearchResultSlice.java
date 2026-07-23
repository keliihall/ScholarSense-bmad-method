package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.List;

public record AuditSearchResultSlice(List<AuditSearchRow> rows, long total) {
    public AuditSearchResultSlice {
        rows = List.copyOf(rows);
        if (total < rows.size()) throw new IllegalArgumentException("AUDIT_SEARCH_TOTAL_INVALID");
    }
}
