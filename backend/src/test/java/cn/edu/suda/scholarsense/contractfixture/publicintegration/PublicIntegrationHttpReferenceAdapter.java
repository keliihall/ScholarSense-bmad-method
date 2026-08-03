package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/** Test-only outbound reference client; it is never assembled as a production bean. */
final class PublicIntegrationHttpReferenceAdapter {

    private final HttpClient http;
    private final Duration requestTimeout;
    private final int maxBodyBytes;
    private final String clientCertificateThumbprint;

    PublicIntegrationHttpReferenceAdapter(
            HttpClient http,
            Duration requestTimeout,
            int maxBodyBytes,
            String clientCertificateThumbprint) {
        this.http = Objects.requireNonNull(http, "http");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("PIC_REDIRECT_POLICY_MUST_BE_NEVER");
        }
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("PIC_REQUEST_TIMEOUT_INVALID");
        }
        if (maxBodyBytes < 1 || maxBodyBytes > 65_536) {
            throw new IllegalArgumentException("PIC_BODY_LIMIT_INVALID");
        }
        this.requestTimeout = requestTimeout;
        this.maxBodyBytes = maxBodyBytes;
        this.clientCertificateThumbprint = requireText(
                clientCertificateThumbprint, "clientCertificateThumbprint");
    }

    Result send(
            URI endpoint,
            byte[] body,
            String idempotencyKey,
            String providerEffectKey,
            WorkloadToken workloadToken) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(workloadToken, "workloadToken");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(providerEffectKey, "providerEffectKey");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("PIC_HTTPS_ENDPOINT_REQUIRED");
        }
        if (body.length > maxBodyBytes) {
            throw new IllegalArgumentException("PIC_BODY_TOO_LARGE");
        }
        if (!clientCertificateThumbprint.equals(workloadToken.certificateThumbprint())) {
            throw new IllegalStateException("PIC_JWT_CNF_CERTIFICATE_MISMATCH");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + workloadToken.serializedToken())
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "identity")
                .header("Content-Digest", contentDigest(body))
                .header("Idempotency-Key", idempotencyKey)
                .header("X-PIC-Contract-Version", "PIC-1.0.0")
                .header("X-Provider-Effect-Key", providerEffectKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] responseBody = response.body();
            if (responseBody != null && responseBody.length > maxBodyBytes) {
                return new Result(Outcome.UNSAFE_RESPONSE, response.statusCode(), new byte[0]);
            }
            return new Result(classify(response.statusCode()), response.statusCode(),
                    responseBody == null ? new byte[0] : responseBody.clone());
        } catch (HttpTimeoutException timeout) {
            return new Result(Outcome.RETRYABLE, 0, new byte[0]);
        } catch (IOException transportFailure) {
            return new Result(Outcome.RETRYABLE, 0, new byte[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRYABLE, 0, new byte[0]);
        }
    }

    static String contentDigest(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "sha-256=:" + Base64.getEncoder().encodeToString(digest) + ":";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Outcome classify(int status) {
        if (status >= 200 && status < 300 && status != 207) {
            return Outcome.CONFIRMED;
        }
        return switch (status) {
            case 207 -> Outcome.UNSUPPORTED_ACK;
            case 301, 302, 303, 307, 308 -> Outcome.UNSAFE_REDIRECT;
            case 401 -> Outcome.AUTHENTICATION_FAILED;
            case 403 -> Outcome.AUTHORIZATION_FAILED;
            case 409 -> Outcome.CONFLICT;
            case 400, 404, 405, 406, 410, 415, 422 -> Outcome.INVALID_REQUEST;
            case 408, 425, 429, 500, 502, 503, 504 -> Outcome.RETRYABLE;
            default -> Outcome.FAILED;
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    enum Outcome {
        CONFIRMED,
        RETRYABLE,
        CONFLICT,
        INVALID_REQUEST,
        AUTHENTICATION_FAILED,
        AUTHORIZATION_FAILED,
        UNSUPPORTED_ACK,
        UNSAFE_REDIRECT,
        UNSAFE_RESPONSE,
        FAILED
    }

    record WorkloadToken(String serializedToken, String certificateThumbprint) {
        WorkloadToken {
            requireText(serializedToken, "serializedToken");
            requireText(certificateThumbprint, "certificateThumbprint");
        }
    }

    record Result(Outcome outcome, int statusCode, byte[] safeResponseBody) {
        Result {
            Objects.requireNonNull(outcome, "outcome");
            safeResponseBody = safeResponseBody.clone();
        }

        @Override
        public byte[] safeResponseBody() {
            return safeResponseBody.clone();
        }
    }
}
