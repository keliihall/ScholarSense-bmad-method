package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityAuthoritySourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityFullSnapshot;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityFullSnapshotSourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import tools.jackson.databind.ObjectMapper;

/** Authenticated, bounded HTTPS transport for signed complete daily snapshots. */
public final class HttpResponsibilityFullSnapshotSourceAdapter
        implements ResponsibilityFullSnapshotSourcePort {
    private static final String SIGNATURE_HEADER =
            "X-Responsibility-Authority-Signature";
    private static final String CONTRACT_HEADER =
            "X-Responsibility-Authority-Contract-Version";

    private final TrustedHttpClient http;
    private final ResponsibilityAuthorityRuntimeProfile profile;
    private final WorkloadIdentityAuthenticationPort workloadIdentity;
    private final IdentitySourceSignaturePort signatures;
    private final ResponsibilityFullSnapshotNormalizer normalizer;

    public HttpResponsibilityFullSnapshotSourceAdapter(
            HttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures) {
        this(TrustedHttpClient.unobserved(http), json, profile, workloadIdentity, signatures);
    }

    public HttpResponsibilityFullSnapshotSourceAdapter(
            TrustedHttpClient http,
            ObjectMapper json,
            ResponsibilityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures) {
        this.http = java.util.Objects.requireNonNull(http);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.workloadIdentity =
                java.util.Objects.requireNonNull(workloadIdentity);
        this.signatures = java.util.Objects.requireNonNull(signatures);
        this.normalizer = new ResponsibilityFullSnapshotNormalizer(json);
        profile.requirePublicHttpsEndpoint();
    }

    @Override
    public ResponsibilityFullSnapshot fetch(
            CheckpointKey key,
            LocalDate businessDate,
            String traceId) {
        return fetchVersion(
                key,
                businessDate,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                traceId);
    }

    @Override
    public ResponsibilityFullSnapshot fetchVersion(
            CheckpointKey key,
            LocalDate businessDate,
            String contractVersion,
            String traceId) {
        requireRequest(
                key, businessDate, contractVersion, traceId);
        String authorization = workloadIdentity.authorizationHeader(
                profile.workloadIdentityReference());
        if (authorization == null
                || authorization.isBlank()
                || authorization.indexOf('\r') >= 0
                || authorization.indexOf('\n') >= 0) {
            throw failure(
                    "RESPONSIBILITY_SOURCE_AUTHENTICATION_FAILED");
        }
        HttpRequest request = HttpRequest.newBuilder(
                        profile.snapshotEndpoint(
                                contractVersion, businessDate))
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
            throw failure(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        } catch (IOException unavailable) {
            throw failure(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        if (response.statusCode() == 401
                || response.statusCode() == 403) {
            close(response.body());
            throw failure(
                    "RESPONSIBILITY_SOURCE_AUTHENTICATION_FAILED");
        }
        if (response.statusCode() != 200) {
            close(response.body());
            throw failure(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        int maximum = profile.maximumResponseBytes();
        if (response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1)
                > maximum) {
            close(response.body());
            throw failure(
                    "RESPONSIBILITY_SOURCE_PAYLOAD_TOO_LARGE");
        }
        byte[] body;
        try (InputStream stream = response.body()) {
            body = stream.readNBytes(maximum + 1);
        } catch (IOException unavailable) {
            throw failure(
                    "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        if (body.length > maximum) {
            Arrays.fill(body, (byte) 0);
            throw failure(
                    "RESPONSIBILITY_SOURCE_PAYLOAD_TOO_LARGE");
        }
        String detachedSignature = response.headers()
                .firstValue(SIGNATURE_HEADER)
                .orElse("");
        boolean verified = !detachedSignature.isBlank()
                && signatures.verify(
                        body,
                        detachedSignature,
                        profile.signatureKeyReference());
        try {
            return normalizer.normalize(
                    body,
                    detachedSignature,
                    verified,
                    contractVersion,
                    key,
                    businessDate,
                    traceId);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private void requireRequest(
            CheckpointKey key,
            LocalDate businessDate,
            String contractVersion,
            String traceId) {
        if (!ResponsibilityAuthoritySourcePort.VERSION_1.equals(
                        contractVersion)
                && !ResponsibilityAuthoritySourcePort.VERSION_2.equals(
                        contractVersion)) {
            throw failure(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        }
        if (key == null
                || !profile.sourceId().equals(key.sourceId())
                || !profile.feedId().equals(key.feedId())
                || !profile.partitionId().equals(key.partitionId())
                || !profile.consumerProjection().equals(
                        key.consumerProjection())
                || businessDate == null
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw failure(
                    "RESPONSIBILITY_SOURCE_REQUEST_INVALID");
        }
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The response is already unusable.
        }
    }

    private static IdentitySyncException failure(String code) {
        return new IdentitySyncException(code);
    }
}
