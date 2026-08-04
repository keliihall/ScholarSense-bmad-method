package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngressPort;

final class ForbiddenAuditAliasConsumer {
    private final AuditLedgerIngressPort ingress;

    ForbiddenAuditAliasConsumer(AuditLedgerIngressPort ingress) {
        this.ingress = ingress;
    }
}
