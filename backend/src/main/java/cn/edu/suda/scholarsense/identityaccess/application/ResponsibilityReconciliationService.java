package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Compares a signed complete source snapshot with one local as-of-watermark view. */
public final class ResponsibilityReconciliationService {
    private static final BigDecimal MINIMUM_MATCH_RATE =
            new BigDecimal("0.999000");

    private final ResponsibilityFullSnapshotSourcePort source;
    private final ResponsibilityReconciliationStorePort store;
    private final IdentitySyncAuditPort audit;
    private final IdentitySyncTransactionPort transactions;
    private final TrustedTimeSource time;

    public ResponsibilityReconciliationService(
            ResponsibilityFullSnapshotSourcePort source,
            ResponsibilityReconciliationStorePort store,
            IdentitySyncAuditPort audit,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time) {
        this.source = java.util.Objects.requireNonNull(source);
        this.store = java.util.Objects.requireNonNull(store);
        this.audit = java.util.Objects.requireNonNull(audit);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.time = java.util.Objects.requireNonNull(time);
    }

    public ResponsibilityReconciliationResult execute(
            RunningResponsibilityReconciliationAttempt attempt,
            Consumer<ResponsibilityReconciliationResult> afterAppend) {
        ResponsibilityFullSnapshot snapshot = source.fetch(
                attempt.key(),
                attempt.businessDate(),
                attempt.traceId());
        validate(snapshot, attempt);
        for (var dependency :
                snapshot.supportingIdentityOrgWatermarks()
                        .entrySet()) {
            String[] route = dependency.getKey().split("\\|", -1);
            if (route.length != 2
                    || store.identityOrgWatermark(
                                    route[0], route[1])
                            < dependency.getValue()) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND");
            }
        }
        List<ResponsibilitySnapshotEntry> actual =
                store.actualSnapshot(
                        attempt.key(),
                        snapshot.throughWatermark(),
                        snapshot.cutoffAt(),
                        snapshot.supportingIdentityOrgWatermarks());
        Instant completedAt = time.now().instant();
        ResponsibilityReconciliationResult result = compare(
                attempt,
                snapshot,
                actual,
                store.openExceptionCount(attempt.key()),
                completedAt);
        transactions.execute(() -> {
            if (!store.leaseIsCurrent(attempt.lease())) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_RECONCILIATION_FENCING_STALE");
            }
            List<ResponsibilityExceptionAuditTransition> transitions =
                    store.appendWithAuditTransitions(
                            result, attempt.lease());
            audit.append(new IdentitySyncAuditEvent(
                    "responsibility.sync.reconciled",
                    "accepted",
                    result.reasonCode(),
                    attempt.jobId(),
                    attempt.attemptNo(),
                    attempt.lease().fencingToken(),
                    result.sourceVersion(),
                    result.throughWatermark(),
                    result.sourceVersion(),
                    result.traceId(),
                    completedAt,
                    Map.of(
                            "identitySessionPolicy", "ISP-1.0.0",
                            "roleFieldPolicy", "RFP-1.0.0",
                            "responsibilityContract",
                            "RESPONSIBILITY-AUTHORITY-1.0.0",
                            "retentionSchedule", "RS-1.0.0"),
                    result.runId()));
            for (ResponsibilityExceptionAuditTransition transition :
                    transitions) {
                audit.append(new IdentitySyncAuditEvent(
                        transition.action(),
                        "accepted",
                        transition.reasonCode(),
                        attempt.jobId(),
                        attempt.attemptNo(),
                        attempt.lease().fencingToken(),
                        result.sourceVersion(),
                        result.throughWatermark(),
                        transition.aggregateVersion(),
                        result.traceId(),
                        completedAt,
                        Map.of(
                                "identitySessionPolicy", "ISP-1.0.0",
                                "roleFieldPolicy", "RFP-1.0.0",
                                "responsibilityContract",
                                "RESPONSIBILITY-AUTHORITY-1.0.0",
                                "retentionSchedule", "RS-1.0.0"),
                        transition.exceptionId()));
            }
            afterAppend.accept(result);
            return null;
        });
        return result;
    }

    static ResponsibilityReconciliationResult compare(
            RunningResponsibilityReconciliationAttempt attempt,
            ResponsibilityFullSnapshot expected,
            List<ResponsibilitySnapshotEntry> actual,
            long exceptionCount,
            Instant completedAt) {
        Index expectedIndex = index(expected.entries());
        Index actualIndex = index(actual);
        Set<String> duplicateKeys = new HashSet<>();
        duplicateKeys.addAll(expectedIndex.duplicates());
        duplicateKeys.addAll(actualIndex.duplicates());
        List<ResponsibilityReconciliationDifference> differences =
                new ArrayList<>();
        for (String key : duplicateKeys.stream().sorted().toList()) {
            ResponsibilitySnapshotEntry entry =
                    expectedIndex.values().getOrDefault(
                            key, actualIndex.values().get(key));
            differences.add(difference(
                    "duplicate",
                    entry,
                    expectedIndex.values().get(key),
                    actualIndex.values().get(key),
                    "RESPONSIBILITY_RECONCILIATION_DIFFERENCES"));
        }
        long matched = 0;
        long missing = 0;
        long unexpected = 0;
        long versionDrift = duplicateKeys.size();
        Set<String> keys = new HashSet<>();
        keys.addAll(expectedIndex.values().keySet());
        keys.addAll(actualIndex.values().keySet());
        for (String key : keys.stream().sorted().toList()) {
            if (duplicateKeys.contains(key)) {
                continue;
            }
            ResponsibilitySnapshotEntry expectedEntry =
                    expectedIndex.values().get(key);
            ResponsibilitySnapshotEntry actualEntry =
                    actualIndex.values().get(key);
            if (expectedEntry == null) {
                unexpected++;
                differences.add(difference(
                        "unexpected",
                        actualEntry,
                        null,
                        actualEntry,
                        "RESPONSIBILITY_RECONCILIATION_DIFFERENCES"));
            } else if (actualEntry == null) {
                missing++;
                differences.add(difference(
                        "missing",
                        expectedEntry,
                        expectedEntry,
                        null,
                        "RESPONSIBILITY_RECONCILIATION_DIFFERENCES"));
            } else if (expectedEntry.recordVersion()
                            != actualEntry.recordVersion()
                    || !expectedEntry.studentSourceRefDigest().equals(
                            actualEntry.studentSourceRefDigest())
                    || !expectedEntry.payloadDigest().equals(
                            actualEntry.payloadDigest())) {
                versionDrift++;
                differences.add(difference(
                        "version-drift",
                        expectedEntry,
                        expectedEntry,
                        actualEntry,
                        "RESPONSIBILITY_RECONCILIATION_DIFFERENCES"));
            } else {
                matched++;
            }
        }
        long denominator =
                matched + missing + unexpected + versionDrift;
        BigDecimal matchRate = denominator == 0
                ? new BigDecimal("1.000000")
                : BigDecimal.valueOf(matched)
                        .divide(
                                BigDecimal.valueOf(denominator),
                                6,
                                RoundingMode.HALF_UP);
        long activeUnmapped = actual.stream()
                .filter(ResponsibilitySnapshotEntry::active)
                .filter(entry -> !entry.recipientMapped())
                .count();
        boolean thresholdFailed =
                matchRate.compareTo(MINIMUM_MATCH_RATE) < 0
                        || activeUnmapped > 0;
        boolean hasDifferences =
                missing + unexpected + versionDrift > 0;
        String outcome = thresholdFailed
                ? "threshold-failed"
                : hasDifferences
                        ? "differences-found"
                        : "matched";
        String reasonCode = switch (outcome) {
            case "matched" ->
                    "RESPONSIBILITY_RECONCILIATION_MATCHED";
            case "differences-found" ->
                    "RESPONSIBILITY_RECONCILIATION_DIFFERENCES";
            default ->
                    "RESPONSIBILITY_RECONCILIATION_THRESHOLD_FAILED";
        };
        return new ResponsibilityReconciliationResult(
                UUID.fromString(UuidV7.generate(completedAt)),
                attempt.jobId(),
                attempt.key(),
                attempt.businessDate(),
                expected.sourceVersion(),
                expected.throughWatermark(),
                expected.supportingIdentityOrgWatermarks(),
                expected.expectedCount(),
                actual.size(),
                expected.canonicalDigest(),
                digest(actual),
                matched,
                missing,
                unexpected,
                versionDrift,
                matchRate,
                activeUnmapped,
                exceptionCount
                        + differences.stream()
                                .map(
                                        ResponsibilityReconciliationDifference
                                                ::studentSourceRefDigest)
                                .distinct()
                                .count(),
                "succeeded",
                outcome,
                reasonCode,
                attempt.lease().fencingToken(),
                attempt.startedAt(),
                completedAt,
                attempt.traceId(),
                differences);
    }

    private static ResponsibilityReconciliationDifference difference(
            String type,
            ResponsibilitySnapshotEntry identity,
            ResponsibilitySnapshotEntry expected,
            ResponsibilitySnapshotEntry actual,
            String reasonCode) {
        return new ResponsibilityReconciliationDifference(
                type,
                identity.relationRefToken(),
                identity.studentSourceRefDigest(),
                expected == null
                        ? null
                        : expected.recordVersion(),
                actual == null ? null : actual.recordVersion(),
                expected == null
                        ? null
                        : expected.payloadDigest(),
                actual == null ? null : actual.payloadDigest(),
                reasonCode);
    }

    private static Index index(
            List<ResponsibilitySnapshotEntry> entries) {
        Map<String, ResponsibilitySnapshotEntry> values =
                new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (ResponsibilitySnapshotEntry entry : entries) {
            if (values.putIfAbsent(
                            entry.relationRefToken(), entry)
                    != null) {
                duplicates.add(entry.relationRefToken());
            }
        }
        return new Index(Map.copyOf(values), Set.copyOf(duplicates));
    }

    public static String digest(
            List<ResponsibilitySnapshotEntry> entries) {
        String canonical = entries.stream()
                .sorted(Comparator.comparing(
                        ResponsibilitySnapshotEntry
                                ::relationRefToken)
                        .thenComparing(
                                ResponsibilitySnapshotEntry
                                        ::studentSourceRefDigest)
                        .thenComparingLong(
                                ResponsibilitySnapshotEntry
                                        ::recordVersion)
                        .thenComparing(
                                ResponsibilitySnapshotEntry
                                        ::payloadDigest))
                .map(entry -> entry.relationRefToken()
                        + "|"
                        + entry.studentSourceRefDigest()
                        + "|"
                        + entry.recordVersion()
                        + "|"
                        + entry.payloadDigest())
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validate(
            ResponsibilityFullSnapshot snapshot,
            RunningResponsibilityReconciliationAttempt attempt) {
        if (!snapshot.key().equals(attempt.key())
                || !snapshot.businessDate().equals(
                        attempt.businessDate())
                || !snapshot.traceId().equals(attempt.traceId())
                || !snapshot.signatureVerified()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SNAPSHOT_SIGNATURE_INVALID");
        }
    }

    private record Index(
            Map<String, ResponsibilitySnapshotEntry> values,
            Set<String> duplicates) {}
}
