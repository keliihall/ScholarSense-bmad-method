package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;

public record AuthorizedAuditSearchRequest(
        String sessionPseudonym,
        AuditSearchView view,
        String objectType,
        String scope,
        String traceId) {}
