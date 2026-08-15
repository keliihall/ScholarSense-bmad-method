package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourcePoisonException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedResponsibilityBatch;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityAuthoritySourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClient;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated, bounded HTTPS transport for responsibility incremental batches. */
public final class HttpResponsibilityAuthoritySourceAdapter
        implements ResponsibilityAuthoritySourcePort {
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String SIGNATURE_HEADER =
            "X-Responsibility-Authority-Signature";
    private static final String CONTRACT_HEADER =
            "X-Responsibility-Authority-Contract-Version";

    private final TrustedHttpClient http;
    private final ObjectMapper json;
    private final ResponsibilityAuthorityRuntimeProfile profile;
    private final WorkloadIdentityAuthenticationPort workloadIdentity;
    private final IdentitySourceSignaturePort signatures;
    private final EnvelopeEncryptionPort encryption;
    private final TrustedTimeSource time;
    private final ResponsibilityAuthorityNormalizer normalizer;

    public HttpResponsibilityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time) {
        this(
                http,
                json,
                profile,
                workloadIdentity,
                signatures,
                encryption,
                pseudonyms,
                time,
                false);
    }

    public HttpResponsibilityAuthoritySourceAdapter(
            TrustedHttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time) {
        this.http = java.util.Objects.requireNonNull(http);
        this.json = java.util.Objects.requireNonNull(json);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.workloadIdentity = java.util.Objects.requireNonNull(workloadIdentity);
        this.signatures = java.util.Objects.requireNonNull(signatures);
        this.encryption = java.util.Objects.requireNonNull(encryption);
        this.time = java.util.Objects.requireNonNull(time);
        this.normalizer = new ResponsibilityAuthorityNormalizer(json, pseudonyms);
        profile.requirePublicHttpsEndpoint();
    }

    HttpResponsibilityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            boolean allowLoopbackHttpForTests) {
        this.http = TrustedHttpClient.unobserved(http);
        this.json = java.util.Objects.requireNonNull(json);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.workloadIdentity =
                java.util.Objects.requireNonNull(workloadIdentity);
        this.signatures = java.util.Objects.requireNonNull(signatures);
        this.encryption = java.util.Objects.requireNonNull(encryption);
        this.time = java.util.Objects.requireNonNull(time);
        this.normalizer =
                new ResponsibilityAuthorityNormalizer(json, pseudonyms);
        if (allowLoopbackHttpForTests) {
            requireLoopbackTestEndpoint(
                    profile.incrementalEndpoint(
                            ResponsibilityAuthoritySourcePort.VERSION_1));
            requireLoopbackTestEndpoint(
                    profile.incrementalEndpoint(
                            ResponsibilityAuthoritySourcePort.VERSION_2));
        } else {
            profile.requirePublicHttpsEndpoint();
        }
    }

    @Override
    public NormalizedResponsibilityBatch fetch(
            CheckpointKey key, long afterWatermark, String traceId) {
        return fetch(
                key,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                afterWatermark,
                traceId);
    }

    @Override
    public NormalizedResponsibilityBatch fetch(
            CheckpointKey key,
            String contractVersion,
            long afterWatermark,
            String traceId) {
        return fetch(
                key,
                contractVersion,
                afterWatermark,
                null,
                traceId);
    }

    @Override
    public NormalizedResponsibilityBatch fetchRange(
            CheckpointKey key,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        return fetchRange(
                key,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                fromInclusive,
                toInclusive,
                traceId);
    }

    @Override
    public NormalizedResponsibilityBatch fetchRange(
            CheckpointKey key,
            String contractVersion,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        if (fromInclusive < 1 || toInclusive < fromInclusive) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_REPLAY_RANGE_INVALID");
        }
        return fetch(
                key,
                contractVersion,
                fromInclusive - 1,
                toInclusive,
                traceId);
    }

    private NormalizedResponsibilityBatch fetch(
            CheckpointKey key,
            String contractVersion,
            long afterWatermark,
            Long throughWatermark,
            String traceId) {
        requireApprovedContract(contractVersion);
        requireExpectedKey(key);
        if (afterWatermark < 0
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_REQUEST_INVALID");
        }
        String authorization = workloadIdentity.authorizationHeader(
                profile.workloadIdentityReference());
        if (authorization == null
                || authorization.isBlank()
                || authorization.indexOf('\r') >= 0
                || authorization.indexOf('\n') >= 0) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_AUTHENTICATION_FAILED");
        }
        String query = "?afterWatermark="
                + URLEncoder.encode(
                        Long.toString(afterWatermark),
                        StandardCharsets.UTF_8)
                + (throughWatermark == null
                        ? ""
                        : "&throughWatermark=" + URLEncoder.encode(
                                Long.toString(throughWatermark),
                                StandardCharsets.UTF_8))
                + "&consumerProjection=responsibility"
                + "&contractVersion=" + URLEncoder.encode(
                        contractVersion, StandardCharsets.UTF_8)
                + "&maximumRecords="
                + profile.maximumRecordsPerBatch();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(profile.incrementalEndpoint(
                                contractVersion) + query))
                .timeout(profile.requestTimeout())
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .header(CONTRACT_HEADER, contractVersion)
                .header("X-ScholarSense-Trace-Id", traceId)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(
                    request, HttpResponse.BodyHandlers.ofInputStream(),
                    "identity-access", traceId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        } catch (IOException unavailable) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            close(response.body());
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_AUTHENTICATION_FAILED");
        }
        if (response.statusCode() != 200) {
            close(response.body());
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        long declaredLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);
        if (declaredLength > MAX_RESPONSE_BYTES) {
            close(response.body());
            throw poison(
                    new byte[0],
                    afterWatermark,
                    "RESPONSIBILITY_SOURCE_PAYLOAD_TOO_LARGE");
        }
        byte[] body;
        try (InputStream stream = response.body()) {
            body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        } catch (IOException unavailable) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            Arrays.fill(body, (byte) 0);
            throw poison(
                    new byte[0],
                    afterWatermark,
                    "RESPONSIBILITY_SOURCE_PAYLOAD_TOO_LARGE");
        }
        String detachedSignature = response.headers()
                .firstValue(SIGNATURE_HEADER)
                .orElse("");
        boolean signatureVerified = !detachedSignature.isBlank()
                && signatures.verify(
                        body,
                        detachedSignature,
                        profile.signatureKeyReference());
        EncryptedSecret secured = encrypt(body);
        try {
            try {
                NormalizedResponsibilityBatch normalized =
                        normalizer.normalize(
                        body,
                        detachedSignature,
                        secured,
                        signatureVerified,
                        key,
                        afterWatermark,
                        throughWatermark,
                        traceId,
                        time.now().instant());
                if (!contractVersion.equals(
                        normalized.contractVersion())) {
                    throw new IdentitySyncException(
                            "RESPONSIBILITY_SOURCE_CONTRACT_VERSION_MISMATCH");
                }
                return normalized;
            } catch (IdentitySyncException failure) {
                throw poison(body, afterWatermark, failure.code());
            }
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private EncryptedSecret encrypt(byte[] body) {
        char[] plaintext =
                new String(body, StandardCharsets.UTF_8).toCharArray();
        try {
            EncryptedSecret secured = encryption.encrypt(
                    plaintext, "responsibility-authority-inbox");
            if (!profile.inboxEncryptionKeyReference().equals(
                    secured.keyRef())) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_SOURCE_KMS_BINDING_INVALID");
            }
            return secured;
        } finally {
            Arrays.fill(plaintext, '\0');
        }
    }

    private IdentitySourcePoisonException poison(
            byte[] body, long afterWatermark, String reasonCode) {
        String payloadDigest =
                ResponsibilityAuthorityNormalizer.digest(body);
        UUID batchId = uuidFromDigest(payloadDigest);
        long sourceVersion = 0;
        long sourceWatermark = afterWatermark;
        try {
            JsonNode root = json.readTree(body);
            JsonNode candidateBatch = root.get("batchId");
            if (candidateBatch != null && candidateBatch.isTextual()) {
                UUID parsed = UUID.fromString(candidateBatch.asText());
                if (parsed.version() == 7 && parsed.variant() == 2) {
                    batchId = parsed;
                }
            }
            JsonNode candidateVersion = root.get("sourceVersion");
            if (candidateVersion != null
                    && candidateVersion.isIntegralNumber()
                    && candidateVersion.asLong() >= 0) {
                sourceVersion = candidateVersion.asLong();
            }
            JsonNode candidateWatermark = root.get("toWatermark");
            if (candidateWatermark != null
                    && candidateWatermark.isIntegralNumber()
                    && candidateWatermark.asLong() >= 0) {
                sourceWatermark = candidateWatermark.asLong();
            }
        } catch (RuntimeException malformed) {
            // Digest and deterministic UUID are sufficient minimal quarantine data.
        }
        return new IdentitySourcePoisonException(
                reasonCode,
                batchId,
                sourceVersion,
                sourceWatermark,
                payloadDigest);
    }

    private void requireExpectedKey(CheckpointKey key) {
        if (key == null
                || !profile.sourceId().equals(key.sourceId())
                || !profile.feedId().equals(key.feedId())
                || !profile.partitionId().equals(key.partitionId())
                || !profile.consumerProjection().equals(
                        key.consumerProjection())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_SCOPE_INVALID");
        }
    }

    private static void requireApprovedContract(
            String contractVersion) {
        if (!ResponsibilityAuthoritySourcePort.VERSION_1.equals(
                        contractVersion)
                && !ResponsibilityAuthoritySourcePort.VERSION_2.equals(
                        contractVersion)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        }
    }

    private static void requireLoopbackTestEndpoint(URI endpoint) {
        if (!"http".equals(endpoint.getScheme())
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SOURCE_ENDPOINT_UNSAFE");
        }
        try {
            for (InetAddress address :
                    InetAddress.getAllByName(endpoint.getHost())) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalArgumentException(
                            "RESPONSIBILITY_SOURCE_ENDPOINT_UNSAFE");
                }
            }
        } catch (java.net.UnknownHostException unresolved) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SOURCE_ENDPOINT_UNSAFE");
        }
    }

    private static UUID uuidFromDigest(String digest) {
        byte[] bytes =
                HexFormat.of().parseHex(digest.substring(0, 32));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int index = 0; index < 8; index++) {
            most = (most << 8) | (bytes[index] & 0xffL);
            least = (least << 8) | (bytes[index + 8] & 0xffL);
        }
        return new UUID(most, least);
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Rejected response bodies are not retained.
        }
    }
}
