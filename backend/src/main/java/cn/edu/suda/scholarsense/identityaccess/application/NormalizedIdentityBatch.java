package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.EmploymentRoleBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record NormalizedIdentityBatch(
        UUID batchId,
        CheckpointKey key,
        String schemaVersion,
        long sourceVersion,
        long fromWatermark,
        long toWatermark,
        Instant sourceVisibleAt,
        Instant observedAt,
        String traceId,
        String mappingVersion,
        String mappingDigest,
        String envelopeDigest,
        String signatureDigest,
        boolean signatureVerified,
        byte[] encryptedEnvelope,
        byte[] wrappedDataKey,
        byte[] encryptionNonce,
        String encryptionKeyRef,
        String encryptionKeyVersion,
        List<AuthoritativeAccount> accounts,
        List<OrganizationNode> organizations,
        List<EmploymentRoleBinding> roleBindings,
        List<IdentitySourceFact> sourceFacts) {
    public NormalizedIdentityBatch {
        if (batchId == null || batchId.version() != 7 || batchId.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_BATCH_UUIDV7_REQUIRED");
        }
        Objects.requireNonNull(key, "key");
        if (!"identity-org".equals(key.consumerProjection())
                || !"IDENTITY-AUTHORITY-BATCH-1.0.0".equals(schemaVersion)
                || sourceVersion < 1
                || fromWatermark < 0
                || toWatermark < fromWatermark) {
            throw new IllegalArgumentException("IDENTITY_BATCH_ENVELOPE_INVALID");
        }
        Objects.requireNonNull(sourceVisibleAt, "sourceVisibleAt");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observedAt.isBefore(sourceVisibleAt)
                || traceId == null || !traceId.matches("[0-9a-f]{32}")
                || !"IDENTITY-ROLE-MAPPING-1.0.0".equals(mappingVersion)) {
            throw new IllegalArgumentException("IDENTITY_BATCH_ENVELOPE_INVALID");
        }
        requireDigest(mappingDigest);
        requireDigest(envelopeDigest);
        requireDigest(signatureDigest);
        encryptedEnvelope = Arrays.copyOf(encryptedEnvelope, encryptedEnvelope.length);
        wrappedDataKey = Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
        encryptionNonce = Arrays.copyOf(encryptionNonce, encryptionNonce.length);
        if (encryptedEnvelope.length == 0
                || wrappedDataKey.length == 0
                || encryptionNonce.length == 0
                || encryptionKeyRef == null
                || !encryptionKeyRef.matches("config://(dev|test|stage|prod)/[a-z0-9-]+")
                || encryptionKeyVersion == null
                || !encryptionKeyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException("IDENTITY_BATCH_ENCRYPTION_INVALID");
        }
        accounts = List.copyOf(accounts);
        organizations = List.copyOf(organizations);
        roleBindings = List.copyOf(roleBindings);
        sourceFacts = List.copyOf(sourceFacts);
        boolean noChange = toWatermark == fromWatermark;
        if ((noChange && (!accounts.isEmpty()
                        || !organizations.isEmpty()
                        || !roleBindings.isEmpty()
                        || !sourceFacts.isEmpty()))
                || (!noChange && sourceFacts.isEmpty())
                || sourceFacts.size()
                        != accounts.size() + organizations.size() + roleBindings.size()) {
            throw new IllegalArgumentException("IDENTITY_BATCH_RECORDS_INCOMPLETE");
        }
    }

    public boolean noChange() {
        return toWatermark == fromWatermark;
    }

    @Override
    public byte[] encryptedEnvelope() {
        return Arrays.copyOf(encryptedEnvelope, encryptedEnvelope.length);
    }

    @Override
    public byte[] wrappedDataKey() {
        return Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
    }

    @Override
    public byte[] encryptionNonce() {
        return Arrays.copyOf(encryptionNonce, encryptionNonce.length);
    }

    public NormalizedIdentityBatch withOrganizations(List<OrganizationNode> replacements) {
        return new NormalizedIdentityBatch(
                batchId, key, schemaVersion, sourceVersion, fromWatermark, toWatermark,
                sourceVisibleAt, observedAt, traceId, mappingVersion, mappingDigest,
                envelopeDigest, signatureDigest, signatureVerified, encryptedEnvelope,
                wrappedDataKey, encryptionNonce,
                encryptionKeyRef, encryptionKeyVersion, accounts, replacements, roleBindings,
                sourceFacts);
    }

    public NormalizedIdentityBatch withAccounts(List<AuthoritativeAccount> replacements) {
        return new NormalizedIdentityBatch(
                batchId, key, schemaVersion, sourceVersion, fromWatermark, toWatermark,
                sourceVisibleAt, observedAt, traceId, mappingVersion, mappingDigest,
                envelopeDigest, signatureDigest, signatureVerified, encryptedEnvelope,
                wrappedDataKey, encryptionNonce,
                encryptionKeyRef, encryptionKeyVersion, replacements, organizations, roleBindings,
                sourceFacts);
    }

    public NormalizedIdentityBatch withAccountsAndSourceFacts(
            List<AuthoritativeAccount> accountReplacements,
            List<IdentitySourceFact> factReplacements) {
        return new NormalizedIdentityBatch(
                batchId, key, schemaVersion, sourceVersion, fromWatermark, toWatermark,
                sourceVisibleAt, observedAt, traceId, mappingVersion, mappingDigest,
                envelopeDigest, signatureDigest, signatureVerified, encryptedEnvelope,
                wrappedDataKey, encryptionNonce,
                encryptionKeyRef, encryptionKeyVersion, accountReplacements, organizations,
                roleBindings, factReplacements);
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_BATCH_DIGEST_INVALID");
        }
    }
}
