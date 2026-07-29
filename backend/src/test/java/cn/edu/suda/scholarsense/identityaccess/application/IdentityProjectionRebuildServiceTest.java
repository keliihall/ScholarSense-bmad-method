package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityProjectionRebuildServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:02:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");

    @Test
    void decryptsContinuousArchiveAndAtomicallyHandsOffAReplacement() {
        NormalizedIdentityBatch batch = batch();
        IdentityArchivedEnvelope archive = new IdentityArchivedEnvelope(
                batch.batchId(),
                KEY,
                batch.schemaVersion(),
                1,
                0,
                1,
                batch.sourceVisibleAt(),
                batch.observedAt(),
                batch.observedAt(),
                batch.traceId(),
                batch.mappingVersion(),
                batch.mappingDigest(),
                batch.envelopeDigest(),
                batch.signatureDigest(),
                new EncryptedSecret(
                        batch.encryptedEnvelope(),
                        batch.wrappedDataKey(),
                        batch.encryptionKeyRef(),
                        batch.encryptionKeyVersion(),
                        batch.encryptionNonce()));
        var target = new CapturingTarget(archive);
        var service = new IdentityProjectionRebuildService(
                target,
                (stored, plaintext, references) -> batch,
                (encrypted, purpose) -> "{}".toCharArray(),
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        target.transactions++;
                        return work.get();
                    }
                },
                IdentityProjectionRebuildServiceTest::trustedNow);

        IdentityProjectionRebuildResult result = service.rebuild(
                KEY, "0123456789abcdef0123456789abcdef");

        assertEquals(1, result.batchCount());
        assertEquals(1, result.factCount());
        assertEquals(1, result.sourceWatermark());
        assertEquals(1, target.transactions);
        assertEquals(List.of(batch), target.replaced);
    }

    private static NormalizedIdentityBatch batch() {
        var interval = new EffectiveInterval(NOW.minusSeconds(120), null);
        var account = new AuthoritativeAccount(
                uuid("901"),
                KEY.sourceId(),
                digest("account"),
                "actor_v1_k1_" + "a".repeat(64),
                AuthoritativeStatus.ACTIVE,
                interval,
                1,
                1);
        var fact = new IdentitySourceFact(
                uuid("902"),
                IdentityRecordKind.ACCOUNT,
                account.externalRefDigest(),
                1,
                interval,
                digest("payload"),
                1);
        return new NormalizedIdentityBatch(
                uuid("903"),
                KEY,
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                1,
                0,
                1,
                NOW.minusSeconds(120),
                NOW.minusSeconds(30),
                "0123456789abcdef0123456789abcdef",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                digest("mapping"),
                digest("{}"),
                digest("signature"),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-authority-inbox",
                "k1",
                List.of(account),
                List.of(),
                List.of(),
                List.of(fact));
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString(
                "019c1234-0000-7000-8000-000000000" + suffix);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class CapturingTarget
            implements IdentityProjectionRebuildPort {
        private final IdentityArchivedEnvelope archive;
        private int transactions;
        private List<NormalizedIdentityBatch> replaced;

        private CapturingTarget(IdentityArchivedEnvelope archive) {
            this.archive = archive;
        }

        @Override
        public List<IdentityArchivedEnvelope> loadArchives(CheckpointKey key) {
            return List.of(archive);
        }

        @Override
        public void replaceCurrentProjection(
                CheckpointKey key,
                List<NormalizedIdentityBatch> batches,
                String traceId,
                Instant rebuiltAt) {
            replaced = List.copyOf(batches);
        }
    }
}
