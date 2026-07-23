package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import java.util.List;

public interface AuditSearchQueryPort {
    AuditSearchSnapshot snapshot();

    AuditSearchResultSlice search(
            AuditSearchCriteria criteria,
            List<String> actorTokens,
            List<String> objectTokens,
            long asOfSequence);
}
