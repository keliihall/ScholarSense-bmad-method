package cn.edu.suda.scholarsense.auditoperations.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Guards, writes once, reads back, recomputes, and only then persists success evidence. */
public final class AuditArchiveService {
    private final AuditArchiveSelectionPort selections;
    private final AuditArchivePort archive;
    private final AuditArchiveEvidencePort evidence;
    private final TrustedAuditTimePort trustedTime;

    public AuditArchiveService(
            AuditArchiveSelectionPort selections,
            AuditArchivePort archive,
            AuditArchiveEvidencePort evidence,
            TrustedAuditTimePort trustedTime) {
        this.selections = Objects.requireNonNull(selections);
        this.archive = Objects.requireNonNull(archive);
        this.evidence = Objects.requireNonNull(evidence);
        this.trustedTime = Objects.requireNonNull(trustedTime);
    }

    public AuditArchiveManifest archive(AuditArchiveRequest request) {
        Objects.requireNonNull(request);
        if (!archive.capabilities().satisfiesProductionBoundary()) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_CAPABILITY_UNAVAILABLE");
        }
        TrustedAuditTime now = trustedTime.current();
        if (!now.fresh()) throw new AuditArchiveException("AUDIT_ARCHIVE_TRUSTED_TIME_STALE");
        if (!request.retentionUntil().isAfter(now.value())) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_RETENTION_INVALID");
        }
        AuditArchiveSelection selection = selections.select(request.sequenceStart(), request.sequenceEnd());
        validateSelection(request, selection);
        byte[] content = encode(selection.records());
        String digest = ArchiveReadResult.sha256(content);
        String objectId = "audit-fixture/" + request.scopeHash() + "/"
                + request.sequenceStart() + "-" + request.sequenceEnd();
        AuditArchiveIntent proposedIntent = new AuditArchiveIntent(
                "AUDIT-ARCHIVE-INTENT-1.0.0",
                request.manifestId(),
                request.fixtureId(),
                request.scopeHash(),
                request.sequenceStart(),
                request.sequenceEnd(),
                selection.records().size(),
                objectId,
                digest,
                request.retentionUntil(),
                request.createdBy(),
                now.value(),
                request.traceId());
        try {
            AuditArchiveIntent preparedIntent = evidence.prepare(proposedIntent);
            if (!sameOperation(proposedIntent, preparedIntent)) {
                throw new IllegalStateException("AUDIT_ARCHIVE_INTENT_CONFLICT");
            }
            proposedIntent = preparedIntent;
        } catch (RuntimeException failed) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_EVIDENCE_PREPARE_FAILED");
        }
        ArchiveObjectVersion object;
        try {
            object = archive.writeOnce(objectId, content, request.retentionUntil());
        } catch (RuntimeException unavailable) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_WRITE_FAILED");
        }
        ArchiveReadResult read;
        try {
            read = archive.read(object.objectId(), object.versionId());
        } catch (RuntimeException unavailable) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_READBACK_FAILED");
        }
        List<AuditArchiveRecord> decoded;
        try {
            decoded = decode(read.content());
        } catch (RuntimeException corrupt) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_READBACK_MISMATCH");
        }
        if (!object.objectId().equals(read.objectId())
                || !object.versionId().equals(read.versionId())
                || !digest.equals(read.contentDigest())
                || !Arrays.equals(content, read.content())
                || !sameRecords(selection.records(), decoded)) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_READBACK_MISMATCH");
        }
        AuditArchiveRecord first = decoded.getFirst();
        AuditArchiveRecord last = decoded.getLast();
        AuditArchiveManifest manifest = new AuditArchiveManifest(
                "AUDIT-ARCHIVE-MANIFEST-1.0.0", "RS-1.0.0", request.manifestId(),
                "audit-domain", request.fixtureId(), request.scopeHash(),
                request.sequenceStart(), request.sequenceEnd(), decoded.size(),
                first.previousHash(), last.entryHash(), object.objectId(), object.versionId(),
                digest, request.createdBy(), proposedIntent.preparedAt(), request.traceId(),
                true, request.retentionUntil());
        try {
            evidence.appendVerified(manifest);
        } catch (RuntimeException failed) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_EVIDENCE_COMMIT_FAILED");
        }
        return manifest;
    }

    private static boolean sameOperation(AuditArchiveIntent proposed, AuditArchiveIntent prepared) {
        return prepared != null
                && proposed.profileVersion().equals(prepared.profileVersion())
                && proposed.manifestId().equals(prepared.manifestId())
                && proposed.fixtureId().equals(prepared.fixtureId())
                && proposed.scopeHash().equals(prepared.scopeHash())
                && proposed.sequenceStart() == prepared.sequenceStart()
                && proposed.sequenceEnd() == prepared.sequenceEnd()
                && proposed.recordCount() == prepared.recordCount()
                && proposed.archiveObjectId().equals(prepared.archiveObjectId())
                && proposed.contentDigest().equals(prepared.contentDigest())
                && proposed.retentionUntil().equals(prepared.retentionUntil())
                && proposed.createdBy().equals(prepared.createdBy())
                && proposed.traceId().equals(prepared.traceId());
    }

    private static void validateSelection(AuditArchiveRequest request, AuditArchiveSelection selection) {
        if (!selection.ledgerHealthy()) throw new AuditArchiveException("AUDIT_ARCHIVE_LEDGER_UNHEALTHY");
        if (!selection.rangeFullyVerified()) throw new AuditArchiveException("AUDIT_ARCHIVE_RANGE_NOT_VERIFIED");
        if (selection.unresolvedPermanentFinding()) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_PERMANENT_FINDING_UNRESOLVED");
        }
        List<AuditArchiveRecord> records = selection.records();
        long expectedCount = request.sequenceEnd() - request.sequenceStart() + 1;
        if (records.size() != expectedCount || records.isEmpty()) {
            throw new AuditArchiveException("AUDIT_ARCHIVE_RANGE_MISMATCH");
        }
        String previousEntryHash = null;
        for (int index = 0; index < records.size(); index++) {
            AuditArchiveRecord record = records.get(index);
            if (record.ledgerSequence() != request.sequenceStart() + index
                    || (previousEntryHash != null && !previousEntryHash.equals(record.previousHash()))) {
                throw new AuditArchiveException("AUDIT_ARCHIVE_CHAIN_MISMATCH");
            }
            previousEntryHash = record.entryHash();
        }
    }

    private static byte[] encode(List<AuditArchiveRecord> records) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF("AUDIT-ARCHIVE-CONTENT-1.0.0");
            output.writeInt(records.size());
            for (AuditArchiveRecord record : records) {
                output.writeLong(record.ledgerSequence());
                output.writeUTF(record.previousHash());
                output.writeUTF(record.entryHash());
                output.writeInt(record.canonicalBytes().length);
                output.write(record.canonicalBytes());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean sameRecords(List<AuditArchiveRecord> expected, List<AuditArchiveRecord> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            AuditArchiveRecord left = expected.get(index);
            AuditArchiveRecord right = actual.get(index);
            if (left.ledgerSequence() != right.ledgerSequence()
                    || !left.previousHash().equals(right.previousHash())
                    || !left.entryHash().equals(right.entryHash())
                    || !Arrays.equals(left.canonicalBytes(), right.canonicalBytes())) {
                return false;
            }
        }
        return true;
    }

    private static List<AuditArchiveRecord> decode(byte[] content) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(content));
            if (!"AUDIT-ARCHIVE-CONTENT-1.0.0".equals(input.readUTF())) {
                throw new IOException("profile");
            }
            int count = input.readInt();
            if (count < 1 || count > 1_000_000) throw new IOException("count");
            List<AuditArchiveRecord> records = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                long sequence = input.readLong();
                String previousHash = input.readUTF();
                String entryHash = input.readUTF();
                int length = input.readInt();
                if (length < 0 || length > 16_777_216) throw new IOException("length");
                records.add(new AuditArchiveRecord(sequence, previousHash, entryHash, input.readNBytes(length)));
            }
            if (input.available() != 0) throw new IOException("trailing");
            return List.copyOf(records);
        } catch (IOException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("AUDIT_ARCHIVE_CONTENT_INVALID", corrupt);
        }
    }
}
