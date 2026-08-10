package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Freezes the domain-owned seal evidence boundary. Cross-validation against the manifest and the
 * currently verified application contract deliberately belongs to the application final-commit
 * guard, not to this domain value.
 */
class DataBatchSealedQualityContractEvidenceTest {

    private static final UUID BATCH_ID = uuid("019ff200-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019ff200-0000-7000-8000-000000000002");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-09T02:00:00.123456Z");
    private static final Instant SEALED_AT = Instant.parse("2026-08-09T02:01:00.123456Z");
    private static final Instant EVALUATED_AT = Instant.parse("2026-08-09T02:02:00.123456Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-09T02:03:00.123456Z");
    private static final Instant QMDP_EFFECTIVE_AT =
            Instant.parse("2026-08-09T02:02:22.000000Z");
    private static final Instant QSHM_EFFECTIVE_AT =
            Instant.parse("2026-08-09T11:24:55.000000Z");
    private static final String MANIFEST_DIGEST = digest('a');

    @Test
    void sealedContractEvidenceIsAnImmutableExactSeventeenFieldDomainRecord() {
        Class<SealedQualityContractEvidence> type = SealedQualityContractEvidence.class;

        assertTrue(type.isRecord());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(
                List.of(
                        "qmdpProfileVersion",
                        "qmdpPolicyRawDigest",
                        "qmdpPolicyCanonicalDigest",
                        "qmdpContractLockVersion",
                        "qmdpContractLockRawDigest",
                        "qmdpContractLockCanonicalDigest",
                        "qmdpAuthorityRef",
                        "qmdpApprovalRef",
                        "qmdpEffectiveAt",
                        "qshmProfileVersion",
                        "qshmProfileRawDigest",
                        "qshmProfileCanonicalDigest",
                        "qshmContractLockVersion",
                        "qshmContractLockRawDigest",
                        "qshmAuthorityRef",
                        "qshmApprovalRef",
                        "qshmEffectiveAt"),
                Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).toList());
        assertEquals(
                List.of(
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, Instant.class, String.class,
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, Instant.class),
                Arrays.stream(type.getRecordComponents()).map(component -> component.getType()).toList());
    }

    @Test
    void everyEvidenceFieldIsRequiredAndBothTimesUseUtcMicrosecondInstants() throws Exception {
        Constructor<SealedQualityContractEvidence> constructor = canonicalConstructor();

        for (int index = 0; index < validEvidenceValues().length; index++) {
            Object[] missingOne = validEvidenceValues();
            missingOne[index] = null;
            assertThrows(
                    InvocationTargetException.class,
                    () -> constructor.newInstance(missingOne),
                    "field " + index + " must not be nullable");
        }

        Object[] subMicrosecondQmdpTime = validEvidenceValues();
        subMicrosecondQmdpTime[8] = QMDP_EFFECTIVE_AT.plusNanos(1);
        assertThrows(
                InvocationTargetException.class,
                () -> constructor.newInstance(subMicrosecondQmdpTime));

        Object[] subMicrosecondQshmTime = validEvidenceValues();
        subMicrosecondQshmTime[16] = QSHM_EFFECTIVE_AT.plusNanos(1);
        assertThrows(
                InvocationTargetException.class,
                () -> constructor.newInstance(subMicrosecondQshmTime));

        SealedQualityContractEvidence evidence = evidence();
        assertEquals(QMDP_EFFECTIVE_AT, evidence.qmdpEffectiveAt());
        assertEquals(QSHM_EFFECTIVE_AT, evidence.qshmEffectiveAt());
        assertEquals(0, evidence.qmdpEffectiveAt().getNano() % 1_000);
        assertEquals(0, evidence.qshmEffectiveAt().getNano() % 1_000);
    }

    @Test
    void sealRequiresEvidenceAndEveryLaterStateKeepsTheExactSameImmutableValue() {
        DataBatch receiving = receiving();
        BatchManifest manifest = manifest();
        SealedQualityContractEvidence evidence = evidence();

        assertNull(receiving.sealedQualityContractEvidence());
        DataBatch sealed = receiving.seal(manifest, evidence, SEALED_AT);
        DataBatch passed = sealed.recordQualityResult(true, EVALUATED_AT);
        DataBatch failed = sealed.recordQualityResult(false, EVALUATED_AT);
        DataBatch published = passed.publish(PUBLISHED_AT);

        assertSame(evidence, sealed.sealedQualityContractEvidence());
        assertSame(evidence, passed.sealedQualityContractEvidence());
        assertSame(evidence, failed.sealedQualityContractEvidence());
        assertSame(evidence, published.sealedQualityContractEvidence());
        assertEquals(evidence, sealed.sealedQualityContractEvidence());
        assertThrows(
                RuntimeException.class,
                () -> receiving().seal(manifest(), null, SEALED_AT));
    }

    @Test
    void publicManifestOnlySealBypassIsRemovedAndResealCannotReplaceEvidence() {
        assertThrows(
                NoSuchMethodException.class,
                () -> DataBatch.class.getMethod("seal", BatchManifest.class, Instant.class));

        DataBatch sealed = receiving().seal(manifest(), evidence(), SEALED_AT);
        SealedQualityContractEvidence original = sealed.sealedQualityContractEvidence();
        SealedQualityContractEvidence replacement = replacementEvidence();

        assertFalse(replacement.equals(sealed.sealedQualityContractEvidence()));
        assertThrows(
                IngestionQualityException.class,
                () -> sealed.seal(manifest(), sealed.sealedQualityContractEvidence(),
                        SEALED_AT.plusSeconds(1)));
        assertThrows(
                IngestionQualityException.class,
                () -> sealed.seal(manifest(), replacement, SEALED_AT.plusSeconds(1)));
        assertSame(original, sealed.sealedQualityContractEvidence());
    }

    private static Constructor<SealedQualityContractEvidence> canonicalConstructor()
            throws NoSuchMethodException {
        return SealedQualityContractEvidence.class.getConstructor(
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, Instant.class, String.class,
                String.class, String.class, String.class, String.class, String.class,
                String.class, Instant.class);
    }

    private static Object[] validEvidenceValues() {
        return new Object[] {
            "QMDP-1.0.0",
            digest('1'),
            digest('2'),
            "EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0",
            digest('3'),
            digest('4'),
            "AUTH-2026-08-08-001",
            "AUTH-2026-08-08-001",
            QMDP_EFFECTIVE_AT,
            "QSHM-1.0.0",
            digest('5'),
            digest('6'),
            "QSHM-CONTRACT-LOCK-1.0.0",
            digest('7'),
            "AUTH-2026-08-09-001",
            "AUTH-2026-08-09-001",
            QSHM_EFFECTIVE_AT
        };
    }

    private static SealedQualityContractEvidence evidence() {
        Object[] value = validEvidenceValues();
        return new SealedQualityContractEvidence(
                (String) value[0], (String) value[1], (String) value[2], (String) value[3],
                (String) value[4], (String) value[5], (String) value[6], (String) value[7],
                (Instant) value[8], (String) value[9], (String) value[10], (String) value[11],
                (String) value[12], (String) value[13], (String) value[14], (String) value[15],
                (Instant) value[16]);
    }

    private static SealedQualityContractEvidence replacementEvidence() {
        Object[] value = validEvidenceValues();
        value[1] = digest('8');
        return new SealedQualityContractEvidence(
                (String) value[0], (String) value[1], (String) value[2], (String) value[3],
                (String) value[4], (String) value[5], (String) value[6], (String) value[7],
                (Instant) value[8], (String) value[9], (String) value[10], (String) value[11],
                (String) value[12], (String) value[13], (String) value[14], (String) value[15],
                (Instant) value[16]);
    }

    private static DataBatch receiving() {
        return DataBatch.receiving(
                BATCH_ID,
                new BatchIdentity("SRC-P0-STUDENT-001", "student-status:2026-08-09", 7),
                BatchLineage.root(LINEAGE_ID, RECEIVED_AT),
                MANIFEST_DIGEST,
                RECEIVED_AT,
                "00112233445566778899aabbccddeeff");
    }

    private static BatchManifest manifest() {
        return new BatchManifest(
                10,
                9,
                1,
                new BatchObservationWindow(RECEIVED_AT.minusSeconds(3600), RECEIVED_AT),
                RECEIVED_AT.plusSeconds(1),
                "Asia/Shanghai",
                digest('8'),
                "STUDENT-1.0.0",
                digest('b'),
                "DCC-1.1.0",
                digest('c'),
                "QG-1.0.0",
                digest('d'),
                "QMDP-1.0.0",
                digest('2'),
                RECEIVED_AT.minusSeconds(1),
                RECEIVED_AT.plusSeconds(60),
                RECEIVED_AT,
                "student-daily",
                MANIFEST_DIGEST);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
