package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.UUID;

/** Storage projection row; token columns never cross the application response projector. */
public record AuditSearchRow(
        UUID recordId,
        long ledgerSequence,
        Instant occurredAt,
        String outcome,
        String factSchemaVersion,
        String policyVersion,
        String retentionScheduleVersion,
        String actorSearchToken,
        String objectType,
        String objectSearchToken,
        String action,
        String traceId,
        String producerModule,
        String eventType,
        String reasonCode,
        String businessActionCategory,
        String businessObjectCategory,
        String rolePackageSummary,
        String projectionScope,
        boolean sourceNetworkRecorded) {}
