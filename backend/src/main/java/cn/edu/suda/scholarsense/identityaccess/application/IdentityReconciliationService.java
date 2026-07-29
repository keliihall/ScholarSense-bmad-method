package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Compares snapshots and records evidence only; it has no mutation port by design. */
public final class IdentityReconciliationService {
    private final IdentityReconciliationPort evidence;
    private final IdentitySyncAuditPort audit;
    private final TrustedTimeSource time;
    private final IdentitySyncTransactionPort transactions;

    public IdentityReconciliationService(
            IdentityReconciliationPort evidence,
            IdentitySyncAuditPort audit,
            TrustedTimeSource time) {
        this(evidence, audit, time, new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return work.get();
            }
        });
    }

    public IdentityReconciliationService(
            IdentityReconciliationPort evidence,
            IdentitySyncAuditPort audit,
            TrustedTimeSource time,
            IdentitySyncTransactionPort transactions) {
        this.evidence = evidence;
        this.audit = audit;
        this.time = time;
        this.transactions = transactions;
    }

    public IdentityReconciliationResult compare(
            IdentityReconciliationSnapshot expected,
            IdentityReconciliationSnapshot actual,
            String traceId) {
        if (!expected.scope().equals(actual.scope())
                || expected.sourceVersion() != actual.sourceVersion()
                || expected.watermark() != actual.watermark()) {
            throw new IllegalArgumentException(
                    "IDENTITY_RECONCILIATION_BASELINE_MISMATCH");
        }
        Map<String, IdentityReconciliationEntry> expectedByKey = index(expected);
        Map<String, IdentityReconciliationEntry> actualByKey = index(actual);
        long missing = expectedByKey.keySet().stream()
                .filter(key -> !actualByKey.containsKey(key)).count();
        long unexpected = actualByKey.keySet().stream()
                .filter(key -> !expectedByKey.containsKey(key)).count();
        long matched = expectedByKey.entrySet().stream()
                .filter(entry -> entry.getValue().equals(actualByKey.get(entry.getKey())))
                .count();
        long versionDrift = expectedByKey.entrySet().stream()
                .filter(entry -> actualByKey.containsKey(entry.getKey()))
                .filter(entry -> !entry.getValue().equals(actualByKey.get(entry.getKey())))
                .count();
        var now = time.now().instant();
        IdentityReconciliationResult result = new IdentityReconciliationResult(
                UUID.fromString(UuidV7.generate(now)),
                expected.scope(),
                expected.sourceVersion(),
                expected.watermark(),
                digest(expected),
                digest(actual),
                expected.entries().size(),
                actual.entries().size(),
                matched,
                missing,
                unexpected,
                versionDrift,
                now,
                traceId,
                false);
        transactions.execute(() -> {
            evidence.append(result);
            audit.append(new IdentitySyncAuditEvent(
                    "identity.sync.reconciled",
                    "accepted",
                    result.matchedCompletely()
                            ? "IDENTITY_RECONCILIATION_MATCHED"
                            : "IDENTITY_RECONCILIATION_DIFFERENCES_FOUND",
                    result.reconciliationId(),
                    1,
                    0,
                    result.sourceVersion(),
                    result.watermark(),
                    result.sourceVersion(),
                    traceId,
                    now,
                    Map.of(
                            "identitySessionPolicy", "ISP-1.0.0",
                            "roleFieldPolicy", "RFP-1.0.0",
                            "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                            "retentionSchedule", "RS-1.0.0")));
            return null;
        });
        return result;
    }

    private static Map<String, IdentityReconciliationEntry> index(
            IdentityReconciliationSnapshot snapshot) {
        return snapshot.entries().stream().collect(Collectors.toUnmodifiableMap(
                IdentityReconciliationEntry::stableKeyDigest,
                Function.identity()));
    }

    private static String digest(IdentityReconciliationSnapshot snapshot) {
        String canonical = snapshot.entries().stream()
                .sorted(Comparator.comparing(IdentityReconciliationEntry::stableKeyDigest))
                .map(entry -> entry.stableKeyDigest()
                        + "|" + entry.recordKind().wireName()
                        + "|" + entry.sourceVersion()
                        + "|" + entry.structureDigest())
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
