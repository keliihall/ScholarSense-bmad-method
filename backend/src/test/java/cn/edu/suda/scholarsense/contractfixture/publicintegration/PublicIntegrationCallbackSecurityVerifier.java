package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Test-only verifier for the exact callback binding frozen by PIC-SEC-1.0.0. */
final class PublicIntegrationCallbackSecurityVerifier {

    private static final String CONTRACT_VERSION = "PIC-1.0.0";
    private static final String CONTENT_TYPE = "application/cloudevents+json";
    private static final String SIGNATURE_LABEL = "sig1";
    private static final String SIGNATURE_ALGORITHM = "ed25519";
    private static final String SIGNATURE_TAG = "scholarsense-pic-callback-v1";
    private static final Duration SIGNATURE_TTL = Duration.ofSeconds(300);
    private static final Duration NONCE_RETENTION = Duration.ofSeconds(600);
    private static final Duration JWT_MAX_TTL = Duration.ofSeconds(300);
    private static final Duration JWT_LEEWAY = Duration.ofSeconds(60);
    private static final List<String> COVERED_COMPONENTS = List.of(
            "@method", "@authority", "@path", "@query", "content-digest",
            "content-type", "x-scholarsense-contract-version",
            "x-scholarsense-callback-timestamp",
            "x-scholarsense-callback-nonce");
    private static final String COVERED_COMPONENT_TEXT = COVERED_COMPONENTS.stream()
            .map(component -> "\"" + component + "\"")
            .reduce((left, right) -> left + " " + right)
            .orElseThrow();
    private static final Pattern SIGNATURE_INPUT = Pattern.compile(
            "^sig1=\\(" + Pattern.quote(COVERED_COMPONENT_TEXT)
                    + "\\);created=([0-9]+);expires=([0-9]+);"
                    + "nonce=\\\"([A-Za-z0-9._:-]{1,128})\\\";"
                    + "keyid=\\\"([A-Za-z0-9._:-]{1,128})\\\";"
                    + "alg=\\\"([A-Za-z0-9._-]{1,32})\\\";"
                    + "tag=\\\"([A-Za-z0-9._:-]{1,128})\\\"$");
    private static final Pattern SIGNATURE_HEADER = Pattern.compile(
            "^sig1=:([A-Za-z0-9+/]{86}==):$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final ReplayStore replayStore;
    private final KeyResolver callbackKeys;
    private final KeyResolver workloadKeys;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final String expectedScope;
    private final Duration allowedSkew;
    private final int maxBodyBytes;

    PublicIntegrationCallbackSecurityVerifier(
            Clock clock,
            ReplayStore replayStore,
            KeyResolver callbackKeys,
            KeyResolver workloadKeys,
            String expectedIssuer,
            String expectedAudience,
            String expectedScope,
            Duration allowedSkew,
            int maxBodyBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.replayStore = Objects.requireNonNull(replayStore, "replayStore");
        this.callbackKeys = Objects.requireNonNull(callbackKeys, "callbackKeys");
        this.workloadKeys = Objects.requireNonNull(workloadKeys, "workloadKeys");
        this.expectedIssuer = requireText(expectedIssuer, "expectedIssuer");
        this.expectedAudience = requireText(expectedAudience, "expectedAudience");
        this.expectedScope = requireText(expectedScope, "expectedScope");
        if (allowedSkew == null || allowedSkew.isNegative()
                || allowedSkew.compareTo(Duration.ofSeconds(300)) > 0) {
            throw new IllegalArgumentException("PIC_CALLBACK_SKEW_INVALID");
        }
        if (maxBodyBytes < 1 || maxBodyBytes > 65_536) {
            throw new IllegalArgumentException("PIC_CALLBACK_BODY_LIMIT_INVALID");
        }
        this.allowedSkew = allowedSkew;
        this.maxBodyBytes = maxBodyBytes;
    }

    Decision verify(CallbackRequest request, SSLSession tlsSession) {
        Objects.requireNonNull(request, "request");
        if (request.body().length > maxBodyBytes) {
            return Decision.BODY_TOO_LARGE;
        }
        String peerCertificateThumbprint = peerCertificateThumbprint(tlsSession);
        if (peerCertificateThumbprint == null) {
            return Decision.CERTIFICATE_BINDING_MISMATCH;
        }
        if (!CONTRACT_VERSION.equals(request.contractVersion())) {
            return Decision.CONTRACT_VERSION_MISMATCH;
        }
        if (!CONTENT_TYPE.equals(request.contentType())) {
            return Decision.CONTENT_TYPE_MISMATCH;
        }
        if (Duration.between(request.callbackTimestamp(), clock.instant()).abs()
                .compareTo(allowedSkew) > 0) {
            return Decision.STALE_TIMESTAMP;
        }
        WorkloadIdentity workload = verifyWorkloadIdentity(request);
        if (workload == null) {
            return Decision.IDENTITY_INVALID;
        }
        if (!expectedIssuer.equals(workload.issuer())) {
            return Decision.IDENTITY_INVALID;
        }
        if (!expectedAudience.equals(workload.audience())) {
            return Decision.AUDIENCE_DENIED;
        }
        if (!expectedScope.equals(workload.scope())) {
            return Decision.SCOPE_DENIED;
        }
        if (!peerCertificateThumbprint.equals(workload.certificateThumbprint())) {
            return Decision.CERTIFICATE_BINDING_MISMATCH;
        }
        SignatureParameters parameters = parseSignatureInput(request.signatureInput());
        if (parameters == null) {
            return Decision.SIGNATURE_INVALID;
        }
        if (!SIGNATURE_ALGORITHM.equals(parameters.algorithm())) {
            return Decision.UNKNOWN_ALGORITHM;
        }
        if (!SIGNATURE_TAG.equals(parameters.tag())
                || !parameters.nonce().equals(request.callbackNonce())
                || !parameters.created().equals(request.callbackTimestamp())
                || !parameters.expires().equals(parameters.created().plus(SIGNATURE_TTL))) {
            return Decision.SIGNATURE_INVALID;
        }
        Duration distance = Duration.between(parameters.created(), clock.instant()).abs();
        if (distance.compareTo(allowedSkew) > 0
                || clock.instant().isAfter(parameters.expires())) {
            return Decision.STALE_TIMESTAMP;
        }
        String actualDigest = PublicIntegrationHttpReferenceAdapter.contentDigest(
                request.body());
        if (!actualDigest.equals(request.contentDigest())) {
            return Decision.DIGEST_MISMATCH;
        }
        PublicKey callbackKey = callbackKeys.resolve(parameters.keyId());
        if (callbackKey == null || !validCallbackSignature(request, callbackKey)) {
            return callbackKey == null ? Decision.UNKNOWN_KEY : Decision.SIGNATURE_INVALID;
        }
        return replayStore.record(new ReplayRegistration(
                workload.subject(), parameters.keyId(), sha256Hex(parameters.nonce()),
                request.contractVersion(), request.source(), request.eventId(),
                request.payloadDigest(), request.technicalResult(), clock.instant(),
                clock.instant().plus(NONCE_RETENTION)));
    }

    static String signatureInput(Instant created, String nonce, String keyId) {
        return SIGNATURE_LABEL + "=(" + COVERED_COMPONENT_TEXT + ");created="
                + created.getEpochSecond() + ";expires="
                + created.plus(SIGNATURE_TTL).getEpochSecond() + ";nonce=\""
                + nonce + "\";keyid=\"" + keyId + "\";alg=\""
                + SIGNATURE_ALGORITHM + "\";tag=\"" + SIGNATURE_TAG + "\"";
    }

    static String signatureBase(CallbackRequest request) {
        SignatureParameters parameters = parseSignatureInput(request.signatureInput());
        if (parameters == null) {
            throw new IllegalArgumentException("PIC_CALLBACK_SIGNATURE_INPUT_INVALID");
        }
        return "\"@method\": " + request.method() + "\n"
                + "\"@authority\": " + request.authority() + "\n"
                + "\"@path\": " + request.path() + "\n"
                + "\"@query\": " + request.query() + "\n"
                + "\"content-digest\": " + request.contentDigest() + "\n"
                + "\"content-type\": " + request.contentType() + "\n"
                + "\"x-scholarsense-contract-version\": "
                + request.contractVersion() + "\n"
                + "\"x-scholarsense-callback-timestamp\": "
                + request.callbackTimestamp() + "\n"
                + "\"x-scholarsense-callback-nonce\": "
                + request.callbackNonce() + "\n"
                + "\"@signature-params\": (" + COVERED_COMPONENT_TEXT + ")"
                + parameters.serializedParameters();
    }

    private WorkloadIdentity verifyWorkloadIdentity(CallbackRequest request) {
        try {
            String[] parts = request.workloadToken().split("\\.", -1);
            if (parts.length != 3) {
                return null;
            }
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode confirmation = claims.get("cnf");
            if (header == null || header.size() != 3
                    || !"at+jwt".equals(text(header, "typ"))
                    || !"EdDSA".equals(text(header, "alg"))
                    || claims == null || claims.size() != 9
                    || confirmation == null || confirmation.size() != 1) {
                return null;
            }
            String keyId = text(header, "kid");
            PublicKey key = workloadKeys.resolve(keyId);
            if (key == null || !validEd25519(
                    key, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                    Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            Instant issuedAt = Instant.ofEpochSecond(number(claims, "iat"));
            Instant notBefore = Instant.ofEpochSecond(number(claims, "nbf"));
            Instant expiresAt = Instant.ofEpochSecond(number(claims, "exp"));
            Instant now = clock.instant();
            if (expiresAt.isBefore(issuedAt)
                    || Duration.between(issuedAt, expiresAt).compareTo(JWT_MAX_TTL) > 0
                    || issuedAt.isAfter(now.plus(JWT_LEEWAY))
                    || now.plus(JWT_LEEWAY).isBefore(notBefore)
                    || now.minus(JWT_LEEWAY).isAfter(expiresAt)) {
                return null;
            }
            return new WorkloadIdentity(
                    text(claims, "iss"), text(claims, "sub"),
                    text(claims, "aud"), text(claims, "scope"),
                    text(claims, "jti"), text(confirmation, "x5t#S256"));
        } catch (IllegalArgumentException | GeneralSecurityException
                | tools.jackson.core.JacksonException invalid) {
            return null;
        }
    }

    private static boolean validCallbackSignature(CallbackRequest request, PublicKey key) {
        Matcher header = SIGNATURE_HEADER.matcher(request.signature());
        if (!header.matches()) {
            return false;
        }
        try {
            return validEd25519(
                    key,
                    signatureBase(request).getBytes(StandardCharsets.UTF_8),
                    Base64.getDecoder().decode(header.group(1)));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validEd25519(PublicKey key, byte[] value, byte[] signature)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(value);
        return verifier.verify(signature);
    }

    private static SignatureParameters parseSignatureInput(String value) {
        Matcher matcher = SIGNATURE_INPUT.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new SignatureParameters(
                    Instant.ofEpochSecond(Long.parseLong(matcher.group(1))),
                    Instant.ofEpochSecond(Long.parseLong(matcher.group(2))),
                    matcher.group(3), matcher.group(4), matcher.group(5), matcher.group(6));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw new IllegalArgumentException("PIC_CALLBACK_JWT_FIELD_INVALID:" + field);
        }
        return value.asText();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("PIC_CALLBACK_JWT_FIELD_INVALID:" + field);
        }
        return value.asLong();
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String peerCertificateThumbprint(SSLSession tlsSession) {
        if (tlsSession == null) {
            return null;
        }
        try {
            Certificate[] certificates = tlsSession.getPeerCertificates();
            if (certificates.length == 0) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    certificates[0].getEncoded());
            return "sha256:" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (SSLPeerUnverifiedException | GeneralSecurityException invalid) {
            return null;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    enum Decision {
        ACCEPT,
        ALREADY_APPLIED,
        REPLAY,
        PAYLOAD_CONFLICT,
        BODY_TOO_LARGE,
        CERTIFICATE_BINDING_MISMATCH,
        CONTRACT_VERSION_MISMATCH,
        CONTENT_TYPE_MISMATCH,
        STALE_TIMESTAMP,
        UNKNOWN_ALGORITHM,
        UNKNOWN_KEY,
        IDENTITY_INVALID,
        AUDIENCE_DENIED,
        SCOPE_DENIED,
        DIGEST_MISMATCH,
        SIGNATURE_INVALID
    }

    @FunctionalInterface
    interface KeyResolver {
        PublicKey resolve(String keyId);
    }

    @FunctionalInterface
    interface ReplayStore {
        Decision record(ReplayRegistration registration);
    }

    static final class InMemoryReplayStore implements ReplayStore {
        private final Map<String, Instant> consumedNonces = new HashMap<>();
        private final Map<String, StoredCallback> inbox = new HashMap<>();

        @Override
        public synchronized Decision record(ReplayRegistration registration) {
            consumedNonces.entrySet().removeIf(
                    item -> item.getValue().isBefore(registration.firstSeenAt()));
            String nonceKey = registration.workloadSubject() + "|"
                    + registration.keyId() + "|" + registration.nonceDigest() + "|"
                    + registration.contractVersion();
            if (consumedNonces.putIfAbsent(
                    nonceKey, registration.retainUntil()) != null) {
                return Decision.REPLAY;
            }
            String inboxKey = registration.source() + "|" + registration.eventId();
            StoredCallback existing = inbox.get(inboxKey);
            if (existing == null) {
                inbox.put(inboxKey, new StoredCallback(
                        registration.payloadDigest(), registration.technicalResult()));
                return Decision.ACCEPT;
            }
            return MessageDigest.isEqual(
                    existing.payloadDigest().getBytes(StandardCharsets.UTF_8),
                    registration.payloadDigest().getBytes(StandardCharsets.UTF_8))
                    ? Decision.ALREADY_APPLIED : Decision.PAYLOAD_CONFLICT;
        }
    }

    record ReplayRegistration(
            String workloadSubject,
            String keyId,
            String nonceDigest,
            String contractVersion,
            String source,
            String eventId,
            String payloadDigest,
            byte[] technicalResult,
            Instant firstSeenAt,
            Instant retainUntil) {
        ReplayRegistration {
            requireText(workloadSubject, "workloadSubject");
            requireText(keyId, "keyId");
            requireText(nonceDigest, "nonceDigest");
            requireText(contractVersion, "contractVersion");
            requireText(source, "source");
            requireText(eventId, "eventId");
            requireText(payloadDigest, "payloadDigest");
            technicalResult = Objects.requireNonNull(
                    technicalResult, "technicalResult").clone();
            Objects.requireNonNull(firstSeenAt, "firstSeenAt");
            Objects.requireNonNull(retainUntil, "retainUntil");
        }

        @Override
        public byte[] technicalResult() {
            return technicalResult.clone();
        }
    }

    private record StoredCallback(String payloadDigest, byte[] technicalResult) {
        private StoredCallback {
            technicalResult = technicalResult.clone();
        }
    }

    private record SignatureParameters(
            Instant created,
            Instant expires,
            String nonce,
            String keyId,
            String algorithm,
            String tag) {
        private String serializedParameters() {
            return ";created=" + created.getEpochSecond()
                    + ";expires=" + expires.getEpochSecond()
                    + ";nonce=\"" + nonce + "\";keyid=\"" + keyId
                    + "\";alg=\"" + algorithm + "\";tag=\"" + tag + "\"";
        }
    }

    private record WorkloadIdentity(
            String issuer,
            String subject,
            String audience,
            String scope,
            String tokenId,
            String certificateThumbprint) {
    }

    record CallbackRequest(
            String method,
            String authority,
            String path,
            String query,
            byte[] body,
            String contentType,
            Instant callbackTimestamp,
            String callbackNonce,
            String signatureInput,
            String signature,
            String contentDigest,
            String contractVersion,
            String workloadToken,
            String source,
            String eventId,
            String payloadDigest,
            byte[] technicalResult) {
        CallbackRequest {
            requireText(method, "method");
            requireText(authority, "authority");
            requireText(path, "path");
            query = Objects.requireNonNull(query, "query");
            body = Objects.requireNonNull(body, "body").clone();
            requireText(contentType, "contentType");
            Objects.requireNonNull(callbackTimestamp, "callbackTimestamp");
            requireText(callbackNonce, "callbackNonce");
            requireText(signatureInput, "signatureInput");
            signature = Objects.requireNonNull(signature, "signature");
            requireText(contentDigest, "contentDigest");
            requireText(contractVersion, "contractVersion");
            requireText(workloadToken, "workloadToken");
            requireText(source, "source");
            requireText(eventId, "eventId");
            requireText(payloadDigest, "payloadDigest");
            technicalResult = Objects.requireNonNull(
                    technicalResult, "technicalResult").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public byte[] technicalResult() {
            return technicalResult.clone();
        }

        CallbackRequest withSignature(String replacement) {
            return copy(body, callbackTimestamp, signatureInput, replacement,
                    contractVersion, workloadToken);
        }

        CallbackRequest withTimestamp(Instant replacement) {
            return copy(body, replacement, signatureInput, signature,
                    contractVersion, workloadToken);
        }

        CallbackRequest withSignatureInput(String replacement) {
            return copy(body, callbackTimestamp, replacement, signature,
                    contractVersion, workloadToken);
        }

        CallbackRequest withWorkloadToken(String replacement) {
            return copy(body, callbackTimestamp, signatureInput, signature,
                    contractVersion, replacement);
        }

        CallbackRequest withBody(byte[] replacement) {
            return copy(replacement, callbackTimestamp, signatureInput, signature,
                    contractVersion, workloadToken);
        }

        CallbackRequest withContractVersion(String replacement) {
            return copy(body, callbackTimestamp, signatureInput, signature,
                    replacement, workloadToken);
        }

        private CallbackRequest copy(
                byte[] replacementBody,
                Instant replacementTimestamp,
                String replacementSignatureInput,
                String replacementSignature,
                String replacementContractVersion,
                String replacementWorkloadToken) {
            return new CallbackRequest(
                    method, authority, path, query, replacementBody, contentType,
                replacementTimestamp, callbackNonce, replacementSignatureInput,
                replacementSignature, contentDigest, replacementContractVersion,
                replacementWorkloadToken,
                source, eventId, payloadDigest, technicalResult);
        }
    }
}
