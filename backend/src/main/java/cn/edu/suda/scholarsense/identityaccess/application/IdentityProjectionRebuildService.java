package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Decrypts the durable archive and atomically replaces the current projection. */
public final class IdentityProjectionRebuildService {
    private final IdentityProjectionRebuildPort rebuild;
    private final IdentityArchiveNormalizationPort normalization;
    private final EnvelopeDecryptionPort decryption;
    private final IdentitySyncTransactionPort transactions;
    private final TrustedTimeSource time;

    public IdentityProjectionRebuildService(
            IdentityProjectionRebuildPort rebuild,
            IdentityArchiveNormalizationPort normalization,
            EnvelopeDecryptionPort decryption,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time) {
        this.rebuild = rebuild;
        this.normalization = normalization;
        this.decryption = decryption;
        this.transactions = transactions;
        this.time = time;
    }

    public IdentityProjectionRebuildResult rebuild(
            CheckpointKey key, String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("IDENTITY_REBUILD_TRACE_INVALID");
        }
        List<IdentityArchivedEnvelope> archives = rebuild.loadArchives(key);
        if (archives.isEmpty() || archives.getFirst().fromWatermark() != 0) {
            throw new IdentitySyncException("IDENTITY_REBUILD_ARCHIVE_INCOMPLETE");
        }
        long expectedWatermark = 0;
        long previousSourceVersion = 0;
        int facts = 0;
        List<NormalizedIdentityBatch> batches = new ArrayList<>();
        RebuildReferences references = new RebuildReferences();
        for (IdentityArchivedEnvelope archive : archives) {
            if (!archive.key().equals(key)
                    || archive.fromWatermark() != expectedWatermark
                    || archive.sourceVersion() <= previousSourceVersion) {
                throw new IdentitySyncException(
                        "IDENTITY_REBUILD_ARCHIVE_INCOMPLETE");
            }
            char[] plaintext = decryption.decrypt(
                    archive.encrypted(), "identity-authority-inbox");
            try {
                NormalizedIdentityBatch batch =
                        normalization.normalize(archive, plaintext, references);
                if (!batch.batchId().equals(archive.batchId())
                        || !batch.envelopeDigest().equals(
                                archive.envelopeDigest())
                        || !batch.signatureVerified()) {
                    throw new IdentitySyncException(
                            "IDENTITY_REBUILD_ARCHIVE_INVALID");
                }
                batches.add(batch);
                facts += batch.sourceFacts().size();
                references.include(batch);
            } finally {
                Arrays.fill(plaintext, '\0');
            }
            expectedWatermark = archive.toWatermark();
            previousSourceVersion = archive.sourceVersion();
        }
        var rebuiltAt = time.now().instant();
        transactions.execute(() -> {
            rebuild.replaceCurrentProjection(key, batches, traceId, rebuiltAt);
            return null;
        });
        return new IdentityProjectionRebuildResult(
                batches.size(),
                facts,
                previousSourceVersion,
                expectedWatermark,
                batches.size());
    }

    private static final class RebuildReferences
            implements IdentityAuthorityReferencePort {
        private final Map<String, AuthoritativeAccount> accounts =
                new LinkedHashMap<>();
        private final Map<String, OrganizationNode> organizations =
                new LinkedHashMap<>();

        void include(NormalizedIdentityBatch batch) {
            batch.accounts().forEach(account ->
                    accounts.put(account.externalRefDigest(), account));
            batch.organizations().forEach(organization ->
                    organizations.put(
                            organization.externalRefDigest(), organization));
        }

        @Override
        public Optional<AuthoritativeAccount> findAccount(
                CheckpointKey key, String externalRefDigest) {
            return Optional.ofNullable(accounts.get(externalRefDigest));
        }

        @Override
        public Optional<OrganizationNode> findOrganization(
                CheckpointKey key, String externalRefDigest) {
            return Optional.ofNullable(organizations.get(externalRefDigest));
        }
    }
}
