package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

public record AuditTokenQueryRequest(
        AuditTokenQueryDomain domain,
        String rawReference,
        Instant retainedFrom) {}
