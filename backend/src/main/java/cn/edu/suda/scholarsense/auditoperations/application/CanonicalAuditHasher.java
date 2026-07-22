package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Audit-owned fixed-field canonical hash implementation; it never hashes Map or database row order accidentally. */
public final class CanonicalAuditHasher {
    public static final String HASH_PROFILE_VERSION = "AUDIT-LEDGER-HASH-1.0.0";
    private static final String DOMAIN_TAG = "scholarsense.audit-ledger.entry.v1";

    public LedgerHash payloadFingerprint(LocalAuditFact fact) {
        return digest(CanonicalJson.canonicalBytes(factMaterial(fact)));
    }

    public LedgerHash entryHash(
            long sequence,
            LedgerHash previousHash,
            LocalAuditOutboxRecord source,
            Instant collectedAt,
            LedgerHash payloadFingerprint) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("domainTag", DOMAIN_TAG);
        material.put("hashProfileVersion", HASH_PROFILE_VERSION);
        material.put("ledgerSequence", sequence);
        material.put("previousHash", previousHash.value());
        material.put("auditId", source.auditId().toString());
        material.put("sourceEventId", source.eventId().toString());
        material.put("producerModule", source.producer());
        material.put("eventType", source.eventType());
        material.put("eventSchemaVersion", source.schemaVersion());
        material.put("factSchemaVersion", source.fact().schemaVersion());
        material.put("sourceCreatedAt", source.createdAt().toString());
        material.put("collectedAt", collectedAt.toString());
        material.put("traceId", source.fact().traceId());
        material.put("aggregateVersion", source.fact().aggregateVersion());
        material.put("payloadFingerprint", payloadFingerprint.value());
        material.put("retentionScheduleVersion", source.fact().retentionScheduleVersion());
        return digest(CanonicalJson.canonicalBytes(material));
    }

    public LedgerHash safeDigest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> factMaterial(LocalAuditFact fact) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("auditId", fact.auditId().toString());
        material.put("schemaVersion", fact.schemaVersion());
        material.put("producerModule", fact.producerModule());
        material.put("actorType", fact.actorType().wireName());
        material.put("actorSearchToken", fact.actorSearchToken());
        material.put("roleIds", fact.roleIds());
        material.put("authorizationContext", fact.authorizationContext());
        material.put("action", fact.action());
        material.put("objectType", fact.objectType());
        material.put("objectSearchToken", fact.objectSearchToken());
        material.put("outcome", fact.outcome());
        material.put("reasonCode", fact.reasonCode());
        material.put("purpose", fact.purpose());
        material.put("projectionScope", fact.projectionScope());
        material.put("occurredAt", fact.occurredAt().toString());
        material.put("recordedAt", fact.recordedAt().toString());
        material.put("timeSourceProfile", timeMaterial(fact.timeSourceProfile()));
        material.put("sourceIpSearchToken", fact.sourceIpSearchToken());
        material.put("tokenizationProfileVersion", fact.tokenizationProfileVersion());
        material.put("keyVersion", fact.keyVersion());
        material.put("traceId", fact.traceId());
        material.put("aggregateType", fact.aggregateType());
        material.put("aggregateIdSearchToken", fact.aggregateIdSearchToken());
        material.put("aggregateVersion", fact.aggregateVersion());
        material.put("idempotencyKeyDigest", fact.idempotencyKeyDigest());
        material.put("policyVersions", fact.policyVersions());
        material.put("retentionScheduleVersion", fact.retentionScheduleVersion());
        return material;
    }

    private static Map<String, Object> timeMaterial(TimeSourceProfile profile) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("sourceId", profile.sourceId());
        material.put("profileVersion", profile.profileVersion());
        material.put("offsetMs", profile.offsetMs());
        material.put("observedAt", profile.observedAt().toString());
        material.put("freshUntil", profile.freshUntil().toString());
        material.put("evidenceRef", profile.evidenceRef());
        return material;
    }

    private static LedgerHash digest(byte[] material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material);
            return new LedgerHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JCA SHA-256 unavailable", impossible);
        }
    }
}
