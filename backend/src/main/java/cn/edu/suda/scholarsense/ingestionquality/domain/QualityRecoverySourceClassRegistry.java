package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Exact, data-owner-approved source-to-recovery-class bindings. */
public record QualityRecoverySourceClassRegistry(
        String registryVersion,
        String contractDigest,
        String authority,
        String status,
        String activation,
        String classificationRule,
        String proposalBasis,
        String bindingCanonicalization,
        String bindingSetDigest,
        String approvalRef,
        String approvedBy,
        OffsetDateTime approvedAt,
        OffsetDateTime effectiveAt,
        Map<String, QualityRecoverySourceClass> bindings,
        String unknownSource,
        String duplicateSource,
        String runtimeEvidenceClaim) {

    public static final String VERSION = "QRSCR-1.0.0";
    public static final String RAW_DIGEST =
            "sha256:35df983b04d3f80482d1bd21776137712f60f934d79480e47fc002fb2148bb3d";
    public static final String BINDING_SET_DIGEST =
            "sha256:dfa05c804f27a4c92edb3c6ed7253c3bdb3fa7d3c6d77be7121a44c716b7aed5";

    private static final OffsetDateTime APPROVED_AT =
            OffsetDateTime.parse("2026-08-12T19:12:19+08:00");
    private static final Map<String, QualityRecoverySourceClass> APPROVED_BINDINGS = Map.ofEntries(
            Map.entry("SRC-P0-ACCOMMODATION-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-CARD-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-CAMPUS-ACCESS-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-DORM-ACCESS-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-DEVICE-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-LEAVE-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-CALENDAR-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P0-TIMETABLE-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P1-OFFCAMPUS-001", QualityRecoverySourceClass.STREAMING),
            Map.entry("SRC-P1-NETWORK-001", QualityRecoverySourceClass.DAILY_BATCH),
            Map.entry("SRC-P1-ACADEMIC-001", QualityRecoverySourceClass.DAILY_BATCH));

    public QualityRecoverySourceClassRegistry {
        requireEquals(VERSION, registryVersion);
        requireEquals(RAW_DIGEST, contractDigest);
        requireEquals(
                "DEC-012/QRP-1.0.0 requires an explicit data-owner-approved executable successor",
                authority);
        requireEquals("approved", status);
        requireEquals(
                "exact-approved-bindings-only;unknown-or-digest-drift-deny", activation);
        requireEquals(
                "explicit-owner-approved-binding-never-derived-from-frequency-slo-ui-or-default",
                classificationRule);
        requireEquals(
                "explicit-user-data-owner-approval-in-bmad-dev-story;DCC-updateFrequency-is-not-approval-evidence",
                proposalBasis);
        requireEquals(
                "sort-by-sourceId-utf8-ascending;sourceId+U+001F+sourceClass+U+000A;sha256-raw-bytes",
                bindingCanonicalization);
        requireEquals(BINDING_SET_DIGEST, bindingSetDigest);
        requireEquals("AUTH-2026-08-12-QRSCR-001", approvalRef);
        requireEquals("Hei", approvedBy);
        requireEquals(APPROVED_AT, approvedAt);
        requireEquals(APPROVED_AT, effectiveAt);
        bindings = Map.copyOf(Objects.requireNonNull(bindings));
        requireEquals(APPROVED_BINDINGS, bindings);
        requireEquals(BINDING_SET_DIGEST, digest(bindings));
        requireEquals("reject", unknownSource);
        requireEquals("reject", duplicateSource);
        requireEquals("contract-only", runtimeEvidenceClaim);
    }

    public QualityRecoverySourceClass requireClass(String sourceId) {
        QualityRecoverySourceClass sourceClass = bindings.get(sourceId);
        if (sourceClass == null) {
            throw new IllegalArgumentException("QUALITY_RECOVERY_SOURCE_CLASS_UNKNOWN");
        }
        return sourceClass;
    }

    private static String digest(Map<String, QualityRecoverySourceClass> bindings) {
        String canonical = bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> entry.getKey() + '\u001f'
                        + entry.getValue().contractValue() + '\n')
                .collect(Collectors.joining());
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw invalid();
        }
    }

    private static void requireEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "QUALITY_RECOVERY_SOURCE_CLASS_REGISTRY_INVALID");
    }
}
