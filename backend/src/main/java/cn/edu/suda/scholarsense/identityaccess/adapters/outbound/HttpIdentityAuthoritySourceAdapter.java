package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthoritySourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityArchiveNormalizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityArchivedEnvelope;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthorityReferencePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityRecordKind;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceFact;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourcePoisonException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedIdentityBatch;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.EmploymentRoleBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClient;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Provider-neutral HTTPS consumer. Raw account and subject values live only during normalization. */
public final class HttpIdentityAuthoritySourceAdapter
        implements IdentityAuthoritySourcePort, IdentityArchiveNormalizationPort {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String SIGNATURE_HEADER = "X-Identity-Authority-Signature";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "$schema", "schemaVersion", "resultType", "sourceId", "feedId", "partitionId",
            "consumerProjection", "batchId", "sourceVersion", "fromWatermark",
            "toWatermark", "sourceVisibleAt", "observedAt", "traceId",
            "correlationId", "mappingVersion", "mappingDigest", "signatureDigest",
            "records");
    private static final Set<String> RECORD_FIELDS = Set.of(
            "eventId", "recordKind", "sourceVersion", "effectiveFrom",
            "effectiveTo", "payloadDigest", "payload");

    private final TrustedHttpClient http;
    private final ObjectMapper json;
    private final IdentityAuthorityRuntimeProfile profile;
    private final WorkloadIdentityAuthenticationPort workloadIdentity;
    private final IdentitySourceSignaturePort signatures;
    private final EnvelopeEncryptionPort encryption;
    private final PseudonymizationPort pseudonyms;
    private final TrustedTimeSource time;
    private final ApprovedIdentityRoleMapping roleMapping;
    private final IdentityAuthorityReferencePort references;
    private final boolean allowLoopbackHttpForTests;

    public HttpIdentityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time) {
        this(
                http, json, profile, workloadIdentity, signatures, encryption,
                pseudonyms, time, ApprovedIdentityRoleMapping.from(profile, json),
                noCurrentReferences(), false);
    }

    public HttpIdentityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            IdentityAuthorityReferencePort references) {
        this(
                http, json, profile, workloadIdentity, signatures, encryption,
                pseudonyms, time, ApprovedIdentityRoleMapping.from(profile, json),
                references, false);
    }

    public HttpIdentityAuthoritySourceAdapter(
            TrustedHttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            IdentityAuthorityReferencePort references) {
        this.http = java.util.Objects.requireNonNull(http);
        this.json = java.util.Objects.requireNonNull(json);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.workloadIdentity = java.util.Objects.requireNonNull(workloadIdentity);
        this.signatures = java.util.Objects.requireNonNull(signatures);
        this.encryption = java.util.Objects.requireNonNull(encryption);
        this.pseudonyms = java.util.Objects.requireNonNull(pseudonyms);
        this.time = java.util.Objects.requireNonNull(time);
        this.roleMapping = ApprovedIdentityRoleMapping.from(profile, json);
        this.references = java.util.Objects.requireNonNull(references);
        this.allowLoopbackHttpForTests = false;
        requireSafeEndpoint(profile.endpoint(), false);
    }

    HttpIdentityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            boolean allowLoopbackHttpForTests) {
        this(
                http,
                json,
                profile,
                workloadIdentity,
                signatures,
                encryption,
                pseudonyms,
                time,
                ApprovedIdentityRoleMapping.from(profile, json),
                noCurrentReferences(),
                allowLoopbackHttpForTests);
    }

    HttpIdentityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            ApprovedIdentityRoleMapping roleMapping,
            boolean allowLoopbackHttpForTests) {
        this(
                http, json, profile, workloadIdentity, signatures, encryption,
                pseudonyms, time, roleMapping, noCurrentReferences(),
                allowLoopbackHttpForTests);
    }

    HttpIdentityAuthoritySourceAdapter(
            HttpClient http,
            ObjectMapper json,
            IdentityAuthorityRuntimeProfile profile,
            WorkloadIdentityAuthenticationPort workloadIdentity,
            IdentitySourceSignaturePort signatures,
            EnvelopeEncryptionPort encryption,
            PseudonymizationPort pseudonyms,
            TrustedTimeSource time,
            ApprovedIdentityRoleMapping roleMapping,
            IdentityAuthorityReferencePort references,
            boolean allowLoopbackHttpForTests) {
        this.http = TrustedHttpClient.unobserved(http);
        this.json = java.util.Objects.requireNonNull(json);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.workloadIdentity = java.util.Objects.requireNonNull(workloadIdentity);
        this.signatures = java.util.Objects.requireNonNull(signatures);
        this.encryption = java.util.Objects.requireNonNull(encryption);
        this.pseudonyms = java.util.Objects.requireNonNull(pseudonyms);
        this.time = java.util.Objects.requireNonNull(time);
        this.roleMapping = java.util.Objects.requireNonNull(roleMapping);
        this.references = java.util.Objects.requireNonNull(references);
        this.allowLoopbackHttpForTests = allowLoopbackHttpForTests;
        requireSafeEndpoint(profile.endpoint(), allowLoopbackHttpForTests);
    }

    @Override
    public NormalizedIdentityBatch fetch(
            CheckpointKey key, long afterWatermark, String traceId) {
        return fetch(key, afterWatermark, null, traceId);
    }

    @Override
    public NormalizedIdentityBatch fetchRange(
            CheckpointKey key,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        if (fromInclusive < 1 || toInclusive < fromInclusive) {
            throw new IdentitySyncException("IDENTITY_REPLAY_RANGE_INVALID");
        }
        return fetch(key, fromInclusive - 1, toInclusive, traceId);
    }

    private NormalizedIdentityBatch fetch(
            CheckpointKey key,
            long afterWatermark,
            Long throughWatermark,
            String traceId) {
        requireExpectedKey(key);
        if (afterWatermark < 0 || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IdentitySyncException("IDENTITY_SOURCE_REQUEST_INVALID");
        }
        String authorization =
                workloadIdentity.authorizationHeader(profile.workloadIdentityReference());
        if (authorization == null
                || authorization.isBlank()
                || authorization.indexOf('\r') >= 0
                || authorization.indexOf('\n') >= 0) {
            throw new IdentitySyncException("IDENTITY_SOURCE_AUTHENTICATION_FAILED");
        }
        String query = "?afterWatermark="
                + URLEncoder.encode(Long.toString(afterWatermark), StandardCharsets.UTF_8)
                + (throughWatermark == null
                        ? ""
                        : "&throughWatermark=" + URLEncoder.encode(
                                Long.toString(throughWatermark), StandardCharsets.UTF_8))
                + "&consumerProjection=identity-org";
        URI endpoint = URI.create(profile.endpoint() + query);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(profile.requestTimeout())
                .header("Accept", "application/json")
                .header("Authorization", authorization)
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
            throw new IdentitySyncException("IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        } catch (IOException unavailable) {
            throw new IdentitySyncException("IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IdentitySyncException("IDENTITY_SOURCE_AUTHENTICATION_FAILED");
        }
        if (response.statusCode() != 200) {
            throw new IdentitySyncException("IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        String detachedSignature =
                response.headers().firstValue(SIGNATURE_HEADER).orElse("");
        long declaredLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);
        if (declaredLength > MAX_RESPONSE_BYTES) {
            try {
                response.body().close();
            } catch (IOException ignoredCloseFailure) {
                // The response is already rejected; no payload is retained.
            }
            throw poison(
                    new byte[0],
                    afterWatermark,
                    new IdentitySyncException(
                            "IDENTITY_SOURCE_PAYLOAD_TOO_LARGE"));
        }
        byte[] body;
        try (InputStream stream = response.body()) {
            body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        } catch (IOException unavailable) {
            throw new IdentitySyncException(
                    "IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE");
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            Arrays.fill(body, (byte) 0);
            throw poison(
                    new byte[0],
                    afterWatermark,
                    new IdentitySyncException(
                            "IDENTITY_SOURCE_PAYLOAD_TOO_LARGE"));
        }
        boolean signatureVerified = !detachedSignature.isBlank()
                && signatures.verify(
                        body, detachedSignature, profile.signatureKeyReference());
        EncryptedSecret secured = encrypt(body);
        try {
            try {
                return normalize(
                        body, detachedSignature, secured, signatureVerified,
                        key, afterWatermark, throughWatermark, traceId,
                        null, null, references);
            } catch (IdentitySyncException failure) {
                throw poison(body, afterWatermark, failure);
            }
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private IdentitySourcePoisonException poison(
            byte[] body, long afterWatermark, IdentitySyncException failure) {
        String payloadDigest = digest(body);
        UUID batchId = uuidFromDigest(payloadDigest);
        long sourceVersion = 0;
        long sourceWatermark = afterWatermark;
        try {
            JsonNode root = json.readTree(body);
            JsonNode candidateBatchId = root.get("batchId");
            if (candidateBatchId != null && candidateBatchId.isTextual()) {
                UUID parsed = UUID.fromString(candidateBatchId.asText());
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
        } catch (RuntimeException ignoredMalformedEnvelope) {
            // The digest and deterministic UUID remain enough to quarantine malformed JSON.
        }
        return new IdentitySourcePoisonException(
                failure.code(), batchId, sourceVersion, sourceWatermark, payloadDigest);
    }

    private static UUID uuidFromDigest(String digest) {
        byte[] bytes = HexFormat.of().parseHex(digest.substring(0, 32));
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

    private NormalizedIdentityBatch normalize(
            byte[] body,
            String detachedSignature,
            EncryptedSecret secured,
            boolean signatureVerified,
            CheckpointKey requestedKey,
            long afterWatermark,
            Long throughWatermark,
            String traceId,
            String acceptedSignatureDigest,
            Instant archivedObservedAt,
            IdentityAuthorityReferencePort normalizationReferences) {
        try {
            JsonNode root = json.readTree(body);
            requireExact(root, ROOT_FIELDS);
            CheckpointKey key = new CheckpointKey(
                    text(root, "sourceId"),
                    text(root, "feedId"),
                    text(root, "partitionId"),
                    text(root, "consumerProjection"));
            requireExpectedKey(key);
            if (!key.equals(requestedKey)
                    || longValue(root, "fromWatermark") != afterWatermark
                    || throughWatermark != null
                        && longValue(root, "toWatermark") != throughWatermark
                    || !traceId.equals(text(root, "traceId"))
                    || !profile.schemaVersion().equals(
                            "IDENTITY-AUTHORITY-PROFILE-1.0.0")
                    || !"IDENTITY-AUTHORITY-BATCH-1.0.0".equals(
                            text(root, "schemaVersion"))
                    || !profile.roleMappingVersion().equals(
                            text(root, "mappingVersion"))
                    || !profile.roleMappingDigest().equals(
                            text(root, "mappingDigest"))) {
                throw invalid("IDENTITY_SOURCE_CONTRACT_UNAPPROVED");
            }
            String computedSignatureDigest = acceptedSignatureDigest == null
                    ? digest(detachedSignature)
                    : acceptedSignatureDigest;
            if (!computedSignatureDigest.equals(stripDigest(
                    text(root, "signatureDigest")))) {
                throw invalid("IDENTITY_SOURCE_SIGNATURE_DIGEST_INVALID");
            }
            long sourceVersion = longValue(root, "sourceVersion");
            long toWatermark = longValue(root, "toWatermark");
            String resultType = text(root, "resultType");
            Instant sourceVisibleAt = Instant.parse(text(root, "sourceVisibleAt"));
            Instant observedAt = archivedObservedAt == null
                    ? time.now().instant()
                    : archivedObservedAt;
            JsonNode records = root.required("records");
            boolean heartbeat = "heartbeat".equals(resultType);
            if (!records.isArray()
                    || records.size() > 1000
                    || (heartbeat && (!records.isEmpty() || toWatermark != afterWatermark))
                    || (!heartbeat
                            && (!"changes".equals(resultType)
                                    || records.isEmpty()
                                    || toWatermark <= afterWatermark))) {
                throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
            }
            long aggregateVersion = sourceVersion;
            List<RecordValue> parsed = new ArrayList<>();
            records.forEach(record -> parsed.add(parseRecord(record, aggregateVersion)));
            Map<String, AuthoritativeAccount> accountsByRef = new HashMap<>();
            Map<String, OrganizationNode> organizationsByRef = new HashMap<>();
            List<IdentitySourceFact> facts = new ArrayList<>();
            for (RecordValue record : parsed) {
                facts.add(record.fact());
                if (record.account() != null
                        && accountsByRef.put(record.rawExternalRef(), record.account()) != null) {
                    throw invalid("IDENTITY_EXTERNAL_ID_DUPLICATE");
                }
                if (record.organization() != null
                        && organizationsByRef.put(
                                record.rawExternalRef(), record.organization()) != null) {
                    throw invalid("IDENTITY_EXTERNAL_ID_DUPLICATE");
                }
            }
            List<EmploymentRoleBinding> roles = new ArrayList<>();
            Set<String> subjectBindings = new java.util.HashSet<>();
            for (AuthoritativeAccount account : accountsByRef.values()) {
                if (!subjectBindings.add(account.subjectBindingToken())) {
                    throw invalid("IDENTITY_SUBJECT_BINDING_CONFLICT");
                }
            }
            for (RecordValue record : parsed) {
                if (record.rolePayload() == null) {
                    continue;
                }
                JsonNode payload = record.rolePayload();
                String accountExternalRef = text(payload, "accountExternalRef");
                String organizationExternalRef =
                        text(payload, "organizationExternalRef");
                AuthoritativeAccount account = accountsByRef.get(accountExternalRef);
                if (account == null) {
                    account = normalizationReferences.findAccount(
                                    key, externalDigest(accountExternalRef))
                            .orElse(null);
                }
                OrganizationNode organization =
                        organizationsByRef.get(organizationExternalRef);
                if (organization == null) {
                    organization = normalizationReferences.findOrganization(
                                    key, externalDigest(organizationExternalRef))
                            .orElse(null);
                }
                if (account == null || organization == null) {
                    throw invalid("IDENTITY_BINDING_REFERENCE_INVALID");
                }
                String sourceRoleCode = text(payload, "sourceRoleCode");
                TargetRole targetRole = approvedRole(
                        sourceRoleCode,
                        text(payload, "targetRoleId"),
                        organization.organizationType());
                roles.add(new EmploymentRoleBinding(
                        stableUuid("role", record.rawExternalRef()),
                        account.accountId(),
                        organization.organizationId(),
                        externalDigest(record.rawExternalRef()),
                        sourceRoleCode,
                        targetRole,
                        text(payload, "mappingVersion"),
                        status(text(payload, "status")),
                        interval(payload),
                        longValue(payload, "recordVersion"),
                        aggregateVersion));
            }
            return new NormalizedIdentityBatch(
                    UUID.fromString(text(root, "batchId")),
                    key,
                    text(root, "schemaVersion"),
                    sourceVersion,
                    afterWatermark,
                    toWatermark,
                    sourceVisibleAt,
                    observedAt,
                    traceId,
                    text(root, "mappingVersion"),
                    stripDigest(text(root, "mappingDigest")),
                    digest(body),
                    computedSignatureDigest,
                    signatureVerified,
                    secured.ciphertext(),
                    secured.wrappedDataKey(),
                    secured.nonce(),
                    secured.keyRef(),
                    secured.keyVersion(),
                    List.copyOf(accountsByRef.values()),
                    List.copyOf(organizationsByRef.values()),
                    roles,
                    facts);
        } catch (IdentitySyncException failure) {
            throw failure;
        } catch (IllegalArgumentException invalidDomainValue) {
            String code = invalidDomainValue.getMessage();
            if (code != null && code.matches("IDENTITY_[A-Z0-9_]{3,120}")) {
                throw new IdentitySyncException(code);
            }
            throw new IdentitySyncException("IDENTITY_SOURCE_PAYLOAD_INVALID");
        } catch (RuntimeException invalid) {
            throw new IdentitySyncException("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
    }

    @Override
    public NormalizedIdentityBatch normalize(
            IdentityArchivedEnvelope archive,
            char[] plaintext,
            IdentityAuthorityReferencePort rebuildReferences) {
        java.nio.ByteBuffer encoded = StandardCharsets.UTF_8
                .encode(java.nio.CharBuffer.wrap(plaintext));
        byte[] body = new byte[encoded.remaining()];
        encoded.get(body);
        try {
            if (!digest(body).equals(archive.envelopeDigest())) {
                throw new IdentitySyncException(
                        "IDENTITY_REBUILD_ARCHIVE_INVALID");
            }
            return normalize(
                    body,
                    "",
                    archive.encrypted(),
                    true,
                    archive.key(),
                    archive.fromWatermark(),
                    archive.toWatermark(),
                    archive.traceId(),
                    archive.signatureDigest(),
                    archive.observedAt(),
                    rebuildReferences);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private RecordValue parseRecord(JsonNode record, long aggregateVersion) {
        requireExact(record, RECORD_FIELDS);
        JsonNode payload = record.required("payload");
        if (!payload.isObject()) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
        String kind = text(record, "recordKind");
        String rawExternalRef = text(payload, "externalRef");
        EffectiveInterval interval = interval(record);
        if (!interval.equals(interval(payload))) {
            throw invalid("IDENTITY_SOURCE_EFFECTIVE_INTERVAL_MISMATCH");
        }
        long sourceVersion = longValue(record, "sourceVersion");
        String payloadDigest = stripDigest(text(record, "payloadDigest"));
        if (!payloadDigest.equals(digest(canonical(payload)))) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_DIGEST_INVALID");
        }
        IdentityRecordKind recordKind = switch (kind) {
            case "account" -> IdentityRecordKind.ACCOUNT;
            case "organization" -> IdentityRecordKind.ORGANIZATION;
            case "employment-role" -> IdentityRecordKind.EMPLOYMENT_ROLE;
            default -> throw invalid("IDENTITY_SOURCE_RECORD_KIND_INVALID");
        };
        IdentitySourceFact fact = new IdentitySourceFact(
                UUID.fromString(text(record, "eventId")),
                recordKind,
                externalDigest(rawExternalRef),
                sourceVersion,
                interval,
                payloadDigest,
                aggregateVersion);
        if (recordKind == IdentityRecordKind.ACCOUNT) {
            List<String> bindings = pseudonyms.pseudonymizeForRead(
                    "identity-actor",
                    text(payload, "issuer") + "\0" + text(payload, "subject"));
            String binding = bindings.isEmpty() ? null : bindings.getFirst();
            return new RecordValue(
                    rawExternalRef,
                    fact,
                    new AuthoritativeAccount(
                            stableUuid("account", rawExternalRef),
                            profile.sourceId(),
                            externalDigest(rawExternalRef),
                            binding,
                            bindings,
                            optionalText(payload, "naturalPersonPrincipalDigest"),
                            optionalText(payload, "naturalPersonAuthorityEvidenceDigest"),
                            status(text(payload, "status")),
                            interval(payload),
                            longValue(payload, "recordVersion"),
                            aggregateVersion),
                    null,
                    null);
        }
        if (recordKind == IdentityRecordKind.ORGANIZATION) {
            String parent = nullableText(payload, "parentExternalRef");
            OrganizationType organizationType = switch (
                    text(payload, "organizationType")) {
                case "school" -> OrganizationType.SCHOOL;
                case "college" -> OrganizationType.COLLEGE;
                case "department" -> OrganizationType.DEPARTMENT;
                default -> throw invalid("IDENTITY_ORGANIZATION_TYPE_INVALID");
            };
            return new RecordValue(
                    rawExternalRef,
                    fact,
                    null,
                    new OrganizationNode(
                            stableUuid("organization", rawExternalRef),
                            profile.sourceId(),
                            externalDigest(rawExternalRef),
                            parent == null ? null : externalDigest(parent),
                            text(payload, "displayName"),
                            organizationType,
                            status(text(payload, "status")),
                            interval(payload),
                            longValue(payload, "recordVersion"),
                            aggregateVersion),
                    null);
        }
        return new RecordValue(rawExternalRef, fact, null, null, payload);
    }

    private TargetRole approvedRole(
            String sourceCode,
            String targetRole,
            OrganizationType organizationType) {
        return roleMapping.resolve(sourceCode, targetRole, organizationType);
    }

    private EncryptedSecret encrypt(byte[] body) {
        char[] plaintext = new String(body, StandardCharsets.UTF_8).toCharArray();
        try {
            EncryptedSecret secured = encryption.encrypt(
                    plaintext, "identity-authority-inbox");
            if (!profile.inboxEncryptionKeyReference().equals(secured.keyRef())) {
                throw new IdentitySyncException("IDENTITY_SOURCE_KMS_BINDING_INVALID");
            }
            return secured;
        } finally {
            Arrays.fill(plaintext, '\0');
        }
    }

    private String externalDigest(String rawExternalRef) {
        String token = pseudonyms.pseudonymize(
                "identity-external-ref", profile.sourceId() + "\0" + rawExternalRef);
        if (token == null || !token.matches("[a-z]+_v1_k[0-9]+_[0-9a-f]{64}")) {
            throw invalid("IDENTITY_EXTERNAL_ID_TOKEN_INVALID");
        }
        return token.substring(token.length() - 64);
    }

    private UUID stableUuid(String kind, String rawExternalRef) {
        byte[] bytes = sha256(
                (kind + "\0" + externalDigest(rawExternalRef))
                        .getBytes(StandardCharsets.UTF_8));
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

    private void requireExpectedKey(CheckpointKey key) {
        if (!profile.sourceId().equals(key.sourceId())
                || !profile.feedId().equals(key.feedId())
                || !profile.partitionId().equals(key.partitionId())
                || !profile.consumerProjection().equals(key.consumerProjection())) {
            throw new IdentitySyncException("IDENTITY_SOURCE_SCOPE_INVALID");
        }
    }

    private static EffectiveInterval interval(JsonNode node) {
        String effectiveTo = nullableText(node, "effectiveTo");
        return new EffectiveInterval(
                Instant.parse(text(node, "effectiveFrom")),
                effectiveTo == null ? null : Instant.parse(effectiveTo));
    }

    private static AuthoritativeStatus status(String value) {
        return switch (value) {
            case "active" -> AuthoritativeStatus.ACTIVE;
            case "inactive" -> AuthoritativeStatus.INACTIVE;
            default -> throw invalid("IDENTITY_STATUS_INVALID");
        };
    }

    private static void requireExact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
        java.util.HashSet<String> actual = new java.util.HashSet<>();
        node.forEachEntry((key, ignored) -> actual.add(key));
        if (!actual.equals(expected) && !(actual.size() == expected.size() - 1
                && !actual.contains("$schema")
                && expected.contains("$schema"))) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber()) {
            throw invalid("IDENTITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asLong();
    }

    private static String stripDigest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid("IDENTITY_SOURCE_DIGEST_INVALID");
        }
        return value.substring("sha256:".length());
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] canonical(JsonNode value) {
        StringBuilder result = new StringBuilder();
        appendCanonical(value, result);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCanonical(JsonNode value, StringBuilder result) {
        if (value.isObject()) {
            java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
            value.forEachEntry(fields::put);
            result.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(toJsonString(field.getKey())).append(':');
                appendCanonical(field.getValue(), result);
            }
            result.append('}');
            return;
        }
        if (value.isArray()) {
            result.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                appendCanonical(value.get(index), result);
            }
            result.append(']');
            return;
        }
        result.append(value);
    }

    private static String toJsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void requireSafeEndpoint(URI endpoint, boolean allowLoopback) {
        boolean loopback = allowLoopback && isTestLoopback(endpoint);
        if ((!loopback && !"https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("IDENTITY_SOURCE_ENDPOINT_INVALID");
        }
    }

    private static boolean isTestLoopback(URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || endpoint.getHost() == null) {
            return false;
        }
        try {
            return InetAddress.getByName(endpoint.getHost()).isLoopbackAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    private static IdentitySyncException invalid(String code) {
        return new IdentitySyncException(code);
    }

    private static IdentityAuthorityReferencePort noCurrentReferences() {
        return new IdentityAuthorityReferencePort() {
            @Override
            public java.util.Optional<AuthoritativeAccount> findAccount(
                    CheckpointKey key, String externalRefDigest) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<OrganizationNode> findOrganization(
                    CheckpointKey key, String externalRefDigest) {
                return java.util.Optional.empty();
            }
        };
    }

    private record RecordValue(
            String rawExternalRef,
            IdentitySourceFact fact,
            AuthoritativeAccount account,
            OrganizationNode organization,
            JsonNode rolePayload) {}
}
