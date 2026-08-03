package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Test-only callback verifier for the direction-specific PIC security profile. */
final class PublicIntegrationCallbackSecurityVerifier {

    private final Clock clock;
    private final NonceStore nonces;
    private final KeyResolver keys;
    private final Duration allowedSkew;
    private final int maxBodyBytes;

    PublicIntegrationCallbackSecurityVerifier(
            Clock clock,
            NonceStore nonces,
            KeyResolver keys,
            Duration allowedSkew,
            int maxBodyBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.keys = Objects.requireNonNull(keys, "keys");
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

    Decision verify(CallbackRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.body().length > maxBodyBytes) {
            return Decision.BODY_TOO_LARGE;
        }
        if (!request.clientCertificateThumbprint().equals(request.jwtCnfThumbprint())) {
            return Decision.CERTIFICATE_BINDING_MISMATCH;
        }
        Duration distance = Duration.between(request.timestamp(), clock.instant()).abs();
        if (distance.compareTo(allowedSkew) > 0) {
            return Decision.STALE_TIMESTAMP;
        }
        if (!"ed25519".equals(request.algorithm())) {
            return Decision.UNKNOWN_ALGORITHM;
        }
        String actualDigest = PublicIntegrationHttpReferenceAdapter.contentDigest(
                request.body());
        if (!actualDigest.equals(request.contentDigest())) {
            return Decision.DIGEST_MISMATCH;
        }
        PublicKey key = keys.resolve(request.keyId());
        if (key == null) {
            return Decision.UNKNOWN_KEY;
        }
        if (!validSignature(request, key)) {
            return Decision.SIGNATURE_INVALID;
        }
        String nonceScope = request.jwtCnfThumbprint() + "|" + request.keyId()
                + "|" + request.nonce() + "|" + request.contractVersion();
        if (!nonces.consume(nonceScope)) {
            return Decision.REPLAY;
        }
        return Decision.ACCEPT;
    }

    static String signatureBase(
            String method,
            String target,
            Instant timestamp,
            String nonce,
            String contentDigest,
            String contractVersion) {
        return method + "\n" + target + "\n" + timestamp + "\n" + nonce
                + "\n" + contentDigest + "\n" + contractVersion;
    }

    private static boolean validSignature(CallbackRequest request, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signatureBase(
                    request.method(), request.target(), request.timestamp(),
                    request.nonce(), request.contentDigest(), request.contractVersion())
                    .getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(request.signature()));
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            return false;
        }
    }

    enum Decision {
        ACCEPT,
        REPLAY,
        BODY_TOO_LARGE,
        CERTIFICATE_BINDING_MISMATCH,
        STALE_TIMESTAMP,
        UNKNOWN_ALGORITHM,
        UNKNOWN_KEY,
        DIGEST_MISMATCH,
        SIGNATURE_INVALID
    }

    @FunctionalInterface
    interface KeyResolver {
        PublicKey resolve(String keyId);
    }

    @FunctionalInterface
    interface NonceStore {
        boolean consume(String scopedNonce);
    }

    static final class InMemoryNonceStore implements NonceStore {
        private final Set<String> consumed = new HashSet<>();

        @Override
        public synchronized boolean consume(String scopedNonce) {
            return consumed.add(scopedNonce);
        }
    }

    record CallbackRequest(
            String method,
            String target,
            byte[] body,
            Instant timestamp,
            String nonce,
            String algorithm,
            String keyId,
            String signature,
            String contentDigest,
            String contractVersion,
            String clientCertificateThumbprint,
            String jwtCnfThumbprint) {
        CallbackRequest {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(target, "target");
            body = Objects.requireNonNull(body, "body").clone();
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(contentDigest, "contentDigest");
            Objects.requireNonNull(contractVersion, "contractVersion");
            Objects.requireNonNull(clientCertificateThumbprint,
                    "clientCertificateThumbprint");
            Objects.requireNonNull(jwtCnfThumbprint, "jwtCnfThumbprint");
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        CallbackRequest withTimestamp(Instant replacement) {
            return copy(body, replacement, nonce, algorithm, jwtCnfThumbprint);
        }

        CallbackRequest withNonceAndAlgorithm(String replacementNonce, String replacementAlgorithm) {
            return copy(body, timestamp, replacementNonce, replacementAlgorithm,
                    jwtCnfThumbprint);
        }

        CallbackRequest withNonceAndJwtCnf(String replacementNonce, String replacementCnf) {
            return copy(body, timestamp, replacementNonce, algorithm, replacementCnf);
        }

        CallbackRequest withNonceAndBody(String replacementNonce, byte[] replacementBody) {
            return copy(replacementBody, timestamp, replacementNonce, algorithm,
                    jwtCnfThumbprint);
        }

        private CallbackRequest copy(
                byte[] replacementBody,
                Instant replacementTimestamp,
                String replacementNonce,
                String replacementAlgorithm,
                String replacementCnf) {
            return new CallbackRequest(
                    method, target, replacementBody, replacementTimestamp,
                    replacementNonce, replacementAlgorithm, keyId, signature,
                    contentDigest, contractVersion, clientCertificateThumbprint,
                    replacementCnf);
        }
    }
}
