package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditArchiveServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
    private static final String ZERO = "0".repeat(64);
    private static final String ONE = "1".repeat(64);
    private static final String TWO = "2".repeat(64);

    @Test
    void writesOnceReadsBackAndOnlyThenAppendsVerifiedEvidence() {
        ImmutableFixtureArchiveAdapter storage = new ImmutableFixtureArchiveAdapter();
        List<AuditArchiveManifest> committed = new ArrayList<>();
        RecordingArchiveEvidence evidence = new RecordingArchiveEvidence(committed);
        AuditArchiveService service = service(storage, healthySelection(), evidence);

        AuditArchiveManifest manifest = service.archive(request());

        assertEquals(2, manifest.recordCount());
        assertEquals(ZERO, manifest.firstPreviousHash());
        assertEquals(TWO, manifest.lastEntryHash());
        assertTrue(manifest.readBackVerified());
        assertEquals(List.of(manifest), committed);
        assertEquals(1, evidence.prepared.size());
        assertEquals(manifest.contentDigest(), evidence.prepared.getFirst().contentDigest());
        assertEquals(manifest.contentDigest(), storage.read(
                manifest.archiveObjectId(), manifest.archiveObjectVersionId()).contentDigest());
    }

    @Test
    void blocksOverwriteRetentionShorteningEarlyDeleteAndHeldDelete() {
        ImmutableFixtureArchiveAdapter storage = new ImmutableFixtureArchiveAdapter();
        AuditArchiveManifest manifest = service(
                storage, healthySelection(), new RecordingArchiveEvidence()).archive(request());

        assertThrows(IllegalStateException.class, () -> storage.writeOnce(
                manifest.archiveObjectId(), "different".getBytes(StandardCharsets.UTF_8),
                plusYears(4)));
        assertThrows(IllegalStateException.class, () -> storage.shortenRetention(
                manifest.archiveObjectId(), manifest.archiveObjectVersionId(), plusYears(1)));
        assertThrows(IllegalStateException.class, () -> storage.delete(
                manifest.archiveObjectId(), manifest.archiveObjectVersionId(), plusYears(3)));
        storage.applyLegalHold(manifest.archiveObjectId(), manifest.archiveObjectVersionId(), "hold-fixture-1");
        assertThrows(IllegalStateException.class, () -> storage.delete(
                manifest.archiveObjectId(), manifest.archiveObjectVersionId(), plusYears(5)));
    }

    @Test
    void tamperOrUnhealthyRangeFailsWithoutSuccessEvidence() {
        ImmutableFixtureArchiveAdapter storage = new ImmutableFixtureArchiveAdapter();
        storage.tamperRead = true;
        List<AuditArchiveManifest> committed = new ArrayList<>();
        AuditArchiveService service = service(
                storage, healthySelection(), new RecordingArchiveEvidence(committed));

        assertEquals("AUDIT_ARCHIVE_READBACK_MISMATCH",
                assertThrows(AuditArchiveException.class, () -> service.archive(request())).code());
        assertTrue(committed.isEmpty());

        AuditArchiveSelection unhealthy = new AuditArchiveSelection(false, true, false, records());
        assertEquals("AUDIT_ARCHIVE_LEDGER_UNHEALTHY", assertThrows(
                AuditArchiveException.class,
                () -> service(new ImmutableFixtureArchiveAdapter(), unhealthy,
                        new RecordingArchiveEvidence(committed)).archive(request())).code());
        assertTrue(committed.isEmpty());
    }

    @Test
    void productionCapabilitiesAreRequiredBeforeAnyWrite() {
        ImmutableFixtureArchiveAdapter storage = new ImmutableFixtureArchiveAdapter();
        storage.capabilities = new AuditArchiveCapabilities(false, true, true, true, true, true);

        assertEquals("AUDIT_ARCHIVE_CAPABILITY_UNAVAILABLE", assertThrows(
                AuditArchiveException.class,
                () -> service(storage, healthySelection(), new RecordingArchiveEvidence())
                        .archive(request())).code());
        assertFalse(storage.hasObjects());
    }

    @Test
    void immutableObjectCanNeverExistWithoutDurableRecoverableIntent() {
        ImmutableFixtureArchiveAdapter storage = new ImmutableFixtureArchiveAdapter();
        RecordingArchiveEvidence evidence = new RecordingArchiveEvidence();
        evidence.failVerified = true;
        AuditArchiveService service = service(storage, healthySelection(), evidence);
        AuditArchiveRequest request = request();

        assertEquals("AUDIT_ARCHIVE_EVIDENCE_COMMIT_FAILED", assertThrows(
                AuditArchiveException.class, () -> service.archive(request)).code());

        assertTrue(storage.hasObjects());
        assertEquals(1, evidence.prepared.size());
        assertTrue(evidence.verified.isEmpty());

        evidence.failVerified = false;
        AuditArchiveManifest recovered = service.archive(request);
        assertEquals(evidence.prepared.getFirst().manifestId(), recovered.manifestId());
        assertEquals(List.of(recovered), evidence.verified);
    }

    private static AuditArchiveService service(
            AuditArchivePort storage,
            AuditArchiveSelection selection,
            AuditArchiveEvidencePort evidence) {
        return new AuditArchiveService(
                (start, end) -> selection,
                storage,
                evidence,
                () -> new TrustedAuditTime(NOW, true));
    }

    private static AuditArchiveRequest request() {
        return new AuditArchiveRequest(
                AuditUuidV7.generate(NOW), "fixture-a", "a".repeat(64), 10, 11,
                "retention-worker", plusYears(4), "a".repeat(32));
    }

    private static Instant plusYears(int years) {
        return NOW.atZone(ZoneOffset.UTC).plusYears(years).toInstant();
    }

    private static AuditArchiveSelection healthySelection() {
        return new AuditArchiveSelection(true, true, false, records());
    }

    private static List<AuditArchiveRecord> records() {
        return List.of(
                new AuditArchiveRecord(10, ZERO, ONE, "record-10".getBytes(StandardCharsets.UTF_8)),
                new AuditArchiveRecord(11, ONE, TWO, "record-11".getBytes(StandardCharsets.UTF_8)));
    }

    /** Test-only storage emulator. It is deliberately not a Spring component. */
    private static final class ImmutableFixtureArchiveAdapter implements AuditArchivePort {
        private final Map<String, Stored> objects = new LinkedHashMap<>();
        private AuditArchiveCapabilities capabilities = AuditArchiveCapabilities.required();
        private boolean tamperRead;

        @Override
        public AuditArchiveCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public ArchiveObjectVersion writeOnce(String objectId, byte[] content, Instant retentionUntil) {
            String version = "fixture-version-1";
            Stored existing = objects.get(objectId);
            if (existing != null) {
                if (!java.util.Arrays.equals(existing.content, content)) {
                    throw new IllegalStateException("AUDIT_ARCHIVE_OVERWRITE_FORBIDDEN");
                }
                return new ArchiveObjectVersion(objectId, version);
            }
            objects.put(objectId, new Stored(content.clone(), retentionUntil, false));
            return new ArchiveObjectVersion(objectId, version);
        }

        @Override
        public ArchiveReadResult read(String objectId, String versionId) {
            Stored stored = objects.get(objectId);
            byte[] content = stored.content.clone();
            if (tamperRead) content[0] ^= 1;
            return ArchiveReadResult.from(objectId, versionId, content);
        }

        void shortenRetention(String objectId, String versionId, Instant until) {
            Stored stored = objects.get(objectId);
            if (until.isBefore(stored.retentionUntil)) {
                throw new IllegalStateException("AUDIT_ARCHIVE_RETENTION_SHORTEN_FORBIDDEN");
            }
        }

        @Override
        public void applyLegalHold(String objectId, String versionId, String holdReference) {
            if (holdReference == null || holdReference.isBlank()) {
                throw new IllegalArgumentException("AUDIT_ARCHIVE_HOLD_INVALID");
            }
            Stored stored = objects.get(objectId);
            objects.put(objectId, new Stored(stored.content, stored.retentionUntil, true));
        }

        void delete(String objectId, String versionId, Instant at) {
            Stored stored = objects.get(objectId);
            if (at.isBefore(stored.retentionUntil) || stored.held) {
                throw new IllegalStateException("AUDIT_ARCHIVE_DELETE_FORBIDDEN");
            }
            objects.remove(objectId);
        }

        boolean hasObjects() {
            return !objects.isEmpty();
        }

        private record Stored(byte[] content, Instant retentionUntil, boolean held) {}
    }

    private static final class RecordingArchiveEvidence implements AuditArchiveEvidencePort {
        private final List<AuditArchiveIntent> prepared = new ArrayList<>();
        private final List<AuditArchiveManifest> verified;
        private boolean failVerified;

        private RecordingArchiveEvidence() {
            this(new ArrayList<>());
        }

        private RecordingArchiveEvidence(List<AuditArchiveManifest> verified) {
            this.verified = verified;
        }

        @Override
        public AuditArchiveIntent prepare(AuditArchiveIntent intent) {
            AuditArchiveIntent existing = prepared.stream()
                    .filter(candidate -> candidate.manifestId().equals(intent.manifestId()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                prepared.add(intent);
                return intent;
            }
            return existing;
        }

        @Override
        public void appendVerified(AuditArchiveManifest manifest) {
            if (failVerified) {
                throw new IllegalStateException("simulated evidence outage");
            }
            if (!verified.contains(manifest)) {
                verified.add(manifest);
            }
        }
    }
}
