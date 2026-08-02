package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AuditSearchToken;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenizationMetadata;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeDecryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommandSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Working non-production security binding backed by a secret-manager-mounted
 * directory. The repository owns validation and cryptographic use; deployment
 * owns material creation, rotation and mount permissions.
 */
public final class MountedIdentitySyncSecurityBindings
        implements WorkloadIdentityAuthenticationPort,
                IdentitySourceSignaturePort,
                EnvelopeEncryptionPort,
                EnvelopeDecryptionPort,
                PseudonymizationPort,
                IdentityAuditTokenPort,
                ResponsibilityV2CutoverCommandSignaturePort {
    private static final String SCHEMA = "IDENTITY-SYNC-SECURITY-BINDING-1.0.0";
    private static final int GCM_NONCE_BYTES = 12;
    private static final byte[] CUTOVER_COMMAND_DOMAIN =
            "RESPONSIBILITY-V2-CUTOVER-COMMAND-MAC-1.0.0"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> MANIFEST_KEYS = Set.of(
            "schemaVersion",
            "workloadIdentityReference",
            "signatureKeyReference",
            "envelopeKeyReference",
            "envelopeKeyVersion",
            "pseudonymCurrentKeyVersion",
            "pseudonymPreviousKeyVersion",
            "auditKeyVersion");

    private final Path directory;
    private final String workloadIdentityReference;
    private final String signatureKeyReference;
    private final String envelopeKeyReference;
    private final String envelopeKeyVersion;
    private final String pseudonymCurrentKeyVersion;
    private final String pseudonymPreviousKeyVersion;
    private final byte[] signatureKey;
    private final byte[] envelopeKey;
    private final String responsibilityWorkloadIdentityReference;
    private final String responsibilitySignatureKeyReference;
    private final String responsibilityEnvelopeKeyReference;
    private final byte[] responsibilitySignatureKey;
    private final byte[] responsibilityEnvelopeKey;
    private final byte[] pseudonymCurrentKey;
    private final byte[] pseudonymPreviousKey;
    private final HmacIdentityAuditTokenAdapter auditTokens;
    private final SecureRandom random;

    public MountedIdentitySyncSecurityBindings(
            Path directory, IdentityAuthorityRuntimeProfile profile) {
        this(directory, profile, null, new SecureRandom());
    }

    public MountedIdentitySyncSecurityBindings(
            Path directory,
            IdentityAuthorityRuntimeProfile identityProfile,
            ResponsibilityAuthorityRuntimeProfile responsibilityProfile) {
        this(
                directory,
                identityProfile,
                java.util.Objects.requireNonNull(responsibilityProfile),
                new SecureRandom());
    }

    MountedIdentitySyncSecurityBindings(
            Path directory,
            IdentityAuthorityRuntimeProfile profile,
            SecureRandom random) {
        this(directory, profile, null, random);
    }

    private MountedIdentitySyncSecurityBindings(
            Path directory,
            IdentityAuthorityRuntimeProfile profile,
            ResponsibilityAuthorityRuntimeProfile responsibilityProfile,
            SecureRandom random) {
        this.directory = requireDirectory(directory);
        this.random = random;
        Properties manifest = loadManifest(this.directory.resolve("manifest.properties"));
        require(manifest, "schemaVersion", SCHEMA);
        require(
                manifest,
                "workloadIdentityReference",
                profile.workloadIdentityReference());
        require(manifest, "signatureKeyReference", profile.signatureKeyReference());
        require(manifest, "envelopeKeyReference", profile.inboxEncryptionKeyReference());
        this.workloadIdentityReference =
                manifest.getProperty("workloadIdentityReference");
        this.signatureKeyReference = manifest.getProperty("signatureKeyReference");
        this.envelopeKeyReference = manifest.getProperty("envelopeKeyReference");
        this.envelopeKeyVersion = keyVersion(manifest, "envelopeKeyVersion");
        this.pseudonymCurrentKeyVersion =
                keyVersion(manifest, "pseudonymCurrentKeyVersion");
        String previous = manifest.getProperty("pseudonymPreviousKeyVersion");
        if (!"none".equals(previous) && !previous.matches("k[0-9]+")) {
            throw invalid();
        }
        if (pseudonymCurrentKeyVersion.equals(previous)) {
            throw invalid();
        }
        this.pseudonymPreviousKeyVersion = previous;
        String auditKeyVersion = keyVersion(manifest, "auditKeyVersion");
        this.signatureKey = readKey("signature-hmac.key");
        this.envelopeKey = readKey("envelope-kek.key");
        this.responsibilityWorkloadIdentityReference =
                responsibilityProfile == null
                        ? null
                        : responsibilityProfile
                                .workloadIdentityReference();
        this.responsibilitySignatureKeyReference =
                responsibilityProfile == null
                        ? null
                        : responsibilityProfile
                                .signatureKeyReference();
        this.responsibilityEnvelopeKeyReference =
                responsibilityProfile == null
                        ? null
                        : responsibilityProfile
                                .inboxEncryptionKeyReference();
        this.responsibilitySignatureKey =
                responsibilityProfile == null
                        ? null
                        : readKey(
                                "responsibility-signature-hmac.key");
        this.responsibilityEnvelopeKey =
                responsibilityProfile == null
                        ? null
                        : readKey(
                                "responsibility-envelope-kek.key");
        this.pseudonymCurrentKey = readKey("pseudonym-current.key");
        this.pseudonymPreviousKey = "none".equals(previous)
                ? null
                : readKey("pseudonym-previous.key");
        this.auditTokens = new HmacIdentityAuditTokenAdapter(
                new SecretKeySpec(readKey("audit-hmac.key"), "HmacSHA256"),
                auditKeyVersion);
        // Read and validate the initial token during bootstrap; the live value
        // is re-read on every request so an external agent can rotate it.
        workloadToken("workload-token");
        if (responsibilityProfile != null) {
            workloadToken("responsibility-workload-token");
        }
    }

    @Override
    public String authorizationHeader(String reference) {
        if (workloadIdentityReference.equals(reference)) {
            return "Bearer " + workloadToken("workload-token");
        }
        if (java.util.Objects.equals(
                responsibilityWorkloadIdentityReference, reference)) {
            return "Bearer "
                    + workloadToken(
                            "responsibility-workload-token");
        }
        throw new IdentitySyncException(
                "IDENTITY_SOURCE_AUTHENTICATION_UNAVAILABLE");
    }

    @Override
    public boolean verify(
            byte[] payload, String detachedSignature, String keyReference) {
        byte[] selectedKey;
        if (signatureKeyReference.equals(keyReference)) {
            selectedKey = signatureKey;
        } else if (java.util.Objects.equals(
                responsibilitySignatureKeyReference,
                keyReference)) {
            selectedKey = responsibilitySignatureKey;
        } else {
            return false;
        }
        if (detachedSignature == null
                || !detachedSignature.matches("[0-9a-f]{64}")) {
            return false;
        }
        byte[] unsigned = unsignedPayload(payload);
        try {
            String expected = HexFormat.of().formatHex(
                    hmac(selectedKey, unsigned));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    detachedSignature.getBytes(StandardCharsets.US_ASCII));
        } finally {
            Arrays.fill(unsigned, (byte) 0);
        }
    }

    @Override
    public String sign(byte[] canonicalPayload) {
        if (responsibilitySignatureKey == null
                || canonicalPayload == null) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_SIGNATURE_UNAVAILABLE");
        }
        byte[] domainSeparated = ByteBuffer.allocate(
                        CUTOVER_COMMAND_DOMAIN.length
                                + 1
                                + canonicalPayload.length)
                .put(CUTOVER_COMMAND_DOMAIN)
                .put((byte) 0)
                .put(canonicalPayload)
                .array();
        try {
            return HexFormat.of().formatHex(
                    hmac(responsibilitySignatureKey, domainSeparated));
        } finally {
            Arrays.fill(domainSeparated, (byte) 0);
        }
    }

    @Override
    public EncryptedSecret encrypt(char[] plaintext, String purpose) {
        boolean responsibility =
                "responsibility-authority-inbox".equals(purpose);
        if (!responsibility
                && !"identity-authority-inbox".equals(purpose)) {
            throw new IdentitySyncException("IDENTITY_SOURCE_KMS_BINDING_INVALID");
        }
        byte[] selectedKey = responsibility
                ? responsibilityEnvelopeKey
                : envelopeKey;
        String selectedReference = responsibility
                ? responsibilityEnvelopeKeyReference
                : envelopeKeyReference;
        if (selectedKey == null || selectedReference == null) {
            throw new IdentitySyncException(
                    "IDENTITY_SOURCE_KMS_BINDING_INVALID");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(plaintext));
        byte[] cleartext = new byte[encoded.remaining()];
        encoded.get(cleartext);
        byte[] dataKey = new byte[32];
        byte[] payloadNonce = new byte[GCM_NONCE_BYTES];
        byte[] wrappingNonce = new byte[GCM_NONCE_BYTES];
        random.nextBytes(dataKey);
        random.nextBytes(payloadNonce);
        random.nextBytes(wrappingNonce);
        try {
            byte[] ciphertext = aesGcm(cleartext, dataKey, payloadNonce);
            byte[] wrapped = aesGcm(
                    dataKey, selectedKey, wrappingNonce);
            byte[] wrappedWithNonce = new byte[wrappingNonce.length + wrapped.length];
            System.arraycopy(
                    wrappingNonce, 0, wrappedWithNonce, 0, wrappingNonce.length);
            System.arraycopy(
                    wrapped, 0, wrappedWithNonce, wrappingNonce.length, wrapped.length);
            return new EncryptedSecret(
                    ciphertext,
                    wrappedWithNonce,
                    selectedReference,
                    envelopeKeyVersion,
                    payloadNonce);
        } catch (GeneralSecurityException unavailable) {
            throw new IdentitySyncException("IDENTITY_SOURCE_KMS_BINDING_INVALID");
        } finally {
            Arrays.fill(cleartext, (byte) 0);
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public char[] decrypt(EncryptedSecret encrypted, String purpose) {
        boolean responsibility =
                "responsibility-authority-inbox".equals(purpose);
        if (!responsibility
                && !"identity-authority-inbox".equals(purpose)) {
            throw new IdentitySyncException(
                    "IDENTITY_SOURCE_KMS_BINDING_INVALID");
        }
        String selectedReference = responsibility
                ? responsibilityEnvelopeKeyReference
                : envelopeKeyReference;
        byte[] selectedKey = responsibility
                ? responsibilityEnvelopeKey
                : envelopeKey;
        if (selectedKey == null
                || !java.util.Objects.equals(
                        selectedReference, encrypted.keyRef())
                || !envelopeKeyVersion.equals(encrypted.keyVersion())) {
            throw new IdentitySyncException("IDENTITY_SOURCE_KMS_BINDING_INVALID");
        }
        byte[] wrapped = encrypted.wrappedDataKey();
        if (wrapped.length <= GCM_NONCE_BYTES) {
            throw new IdentitySyncException("IDENTITY_SOURCE_KMS_BINDING_INVALID");
        }
        byte[] wrappingNonce = Arrays.copyOf(wrapped, GCM_NONCE_BYTES);
        byte[] wrappedKey = Arrays.copyOfRange(
                wrapped, GCM_NONCE_BYTES, wrapped.length);
        byte[] dataKey = null;
        byte[] cleartext = null;
        try {
            dataKey = aesGcmDecrypt(
                    wrappedKey, selectedKey, wrappingNonce);
            cleartext = aesGcmDecrypt(
                    encrypted.ciphertext(), dataKey, encrypted.nonce());
            return StandardCharsets.UTF_8.decode(
                            ByteBuffer.wrap(cleartext))
                    .toString()
                    .toCharArray();
        } catch (GeneralSecurityException unavailable) {
            throw new IdentitySyncException("IDENTITY_SOURCE_KMS_BINDING_INVALID");
        } finally {
            Arrays.fill(wrappingNonce, (byte) 0);
            Arrays.fill(wrappedKey, (byte) 0);
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
            }
            if (cleartext != null) {
                Arrays.fill(cleartext, (byte) 0);
            }
        }
    }

    @Override
    public String pseudonymize(String purpose, String rawValue) {
        return token(purpose, rawValue, pseudonymCurrentKeyVersion, pseudonymCurrentKey);
    }

    @Override
    public List<String> pseudonymizeForRead(String purpose, String rawValue) {
        List<String> values = new ArrayList<>();
        values.add(pseudonymize(purpose, rawValue));
        if (pseudonymPreviousKey != null) {
            values.add(token(
                    purpose,
                    rawValue,
                    pseudonymPreviousKeyVersion,
                    pseudonymPreviousKey));
        }
        return List.copyOf(values);
    }

    @Override
    public AuditSearchToken tokenize(
            AuditTokenDomain domain, String normalizedValue) {
        return auditTokens.tokenize(domain, normalizedValue);
    }

    @Override
    public AuditTokenizationMetadata metadata() {
        return auditTokens.metadata();
    }

    private String token(
            String purpose, String rawValue, String keyVersion, byte[] key) {
        if (purpose == null || rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_PSEUDONYM_INPUT_INVALID");
        }
        String prefix = switch (purpose) {
            case "identity-actor" -> "actor";
            case "identity-external-ref" -> "external";
            case "browser-binding" -> "browser";
            case "identity-session" -> "session";
            default -> throw new IllegalArgumentException(
                    "IDENTITY_PSEUDONYM_PURPOSE_INVALID");
        };
        byte[] value = (purpose + "\0" + rawValue)
                .getBytes(StandardCharsets.UTF_8);
        try {
            return prefix + "_v1_" + keyVersion + "_"
                    + HexFormat.of().formatHex(hmac(key, value));
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private String workloadToken(String name) {
        byte[] value = readSecret(name, 32, 8192);
        try {
            String token = new String(value, StandardCharsets.US_ASCII).strip();
            if (!token.matches("[A-Za-z0-9._~+/-]{24,8192}")) {
                throw invalid();
            }
            return token;
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private byte[] readKey(String name) {
        return readSecret(name, 32, 64);
    }

    private byte[] readSecret(String name, int minimum, int maximum) {
        Path file = directory.resolve(name);
        requireProtectedRegularFile(file);
        try {
            byte[] value = Files.readAllBytes(file);
            if (value.length < minimum || value.length > maximum) {
                Arrays.fill(value, (byte) 0);
                throw invalid();
            }
            return value;
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "IDENTITY_SYNC_SECURITY_BINDING_UNAVAILABLE", unavailable);
        }
    }

    private static Properties loadManifest(Path file) {
        requireProtectedRegularFile(file);
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            values.load(stream);
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "IDENTITY_SYNC_SECURITY_BINDING_UNAVAILABLE", unavailable);
        }
        if (!values.stringPropertyNames().equals(MANIFEST_KEYS)) {
            throw invalid();
        }
        return values;
    }

    private static Path requireDirectory(Path directory) {
        if (directory == null
                || !directory.isAbsolute()
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw invalid();
        }
        return directory.normalize();
    }

    private static void requireProtectedRegularFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw invalid();
        }
        try {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw invalid();
            }
        } catch (UnsupportedOperationException ignoredNonPosixFileSystem) {
            // File identity and no-follow checks still apply on non-POSIX hosts.
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "IDENTITY_SYNC_SECURITY_BINDING_UNAVAILABLE", unavailable);
        }
    }

    private static byte[] unsignedPayload(byte[] payload) {
        String value = new String(payload, StandardCharsets.UTF_8);
        return value.replaceFirst(
                        "\"signatureDigest\"\\s*:\\s*\"sha256:[0-9a-f]{64}\"",
                        "\"signatureDigest\":\"sha256:" + "0".repeat(64) + "\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    "IDENTITY_SYNC_SECURITY_BINDING_UNAVAILABLE", unavailable);
        }
    }

    private static byte[] aesGcm(byte[] value, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(value);
    }

    private static byte[] aesGcmDecrypt(
            byte[] value, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(value);
    }

    private static String keyVersion(Properties values, String name) {
        String value = values.getProperty(name);
        if (value == null || !value.matches("k[0-9]+")) {
            throw invalid();
        }
        return value;
    }

    private static void require(
            Properties values, String name, String expected) {
        if (!expected.equals(values.getProperty(name))) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("IDENTITY_SYNC_SECURITY_BINDING_INVALID");
    }
}
