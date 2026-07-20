package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;

public record IdentityAuditRecord(LocalAuditFact fact, LocalAuditOutboxRecord outbox) {}
