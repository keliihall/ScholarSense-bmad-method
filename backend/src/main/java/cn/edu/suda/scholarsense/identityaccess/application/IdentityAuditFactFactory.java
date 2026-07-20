package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeException;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class IdentityAuditFactFactory {
    private final TrustedTimeSource timeSource;
    private final IdentityAuditTokenPort tokens;

    public IdentityAuditFactFactory(TrustedTimeSource timeSource, IdentityAuditTokenPort tokens) {
        this.timeSource = timeSource;
        this.tokens = tokens;
    }

    public IdentityAuditRecord create(IdentityAuditRequest request) {
        TrustedTime occurred;
        try {
            IdentityAuditVocabulary.validate(request);
            occurred = timeSource.now();
        } catch (TrustedTimeException unavailable) {
            throw unavailable("IDENTITY_AUDIT_TIME_UNAVAILABLE");
        } catch (IllegalArgumentException invalid) {
            throw unavailable("IDENTITY_AUDIT_CONTRACT_INVALID");
        }
        try {
            List<AuditSearchToken> generated = new ArrayList<>();
            AuditSearchToken actor = token(request.actorType() == ActorType.ANONYMOUS
                    ? null : request.actorIdentity(), AuditTokenDomain.ACTOR, generated);
            AuditSearchToken object = token(request.objectIdentity(), AuditTokenDomain.OBJECT, generated);
            AuditSearchToken sourceIp = token(request.sourceIp(), AuditTokenDomain.SOURCE_IP, generated);
            AuditSearchToken aggregate = token(request.aggregateIdentity(), AuditTokenDomain.AGGREGATE, generated);
            AuditTokenizationMetadata metadata = metadata(generated);
            TrustedTime recorded = timeSource.now();
            UUID auditId = UUID.fromString(UuidV7.generate(occurred.instant()));
            LocalAuditFact fact = new LocalAuditFact(
                    auditId,
                    "LOCAL-AUDIT-FACT-1.0.0",
                    "identity-access",
                    request.actorType(),
                    value(actor),
                    request.roleIds(),
                    request.authorizationContext().asFactFields(),
                    request.action().code(),
                    request.objectType(),
                    value(object),
                    request.outcome(),
                    request.reasonCode(),
                    request.purpose(),
                    request.projectionScope(),
                    occurred.instant(),
                    recorded.instant(),
                    occurred.profile(),
                    value(sourceIp),
                    metadata.profileVersion(),
                    metadata.keyVersion(),
                    request.traceId(),
                    request.aggregateType(),
                    value(aggregate),
                    request.aggregateVersion(),
                    digestOrNull(request.idempotencyKey()),
                    request.policyVersions(),
                    "RS-1.0.0");
            LocalAuditOutboxRecord outbox = LocalAuditOutboxRecord.forFact(
                    UUID.fromString(UuidV7.generate(recorded.instant())), fact, recorded.instant());
            return new IdentityAuditRecord(fact, outbox);
        } catch (TrustedTimeException unavailable) {
            throw unavailable("IDENTITY_AUDIT_TIME_UNAVAILABLE");
        } catch (IdentityAccessException failure) {
            throw failure;
        } catch (RuntimeException invalidOrUnavailable) {
            throw unavailable("IDENTITY_AUDIT_TOKENIZATION_UNAVAILABLE");
        }
    }

    private AuditSearchToken token(
            String normalizedValue,
            AuditTokenDomain domain,
            List<AuditSearchToken> generated) {
        if (normalizedValue == null) {
            return null;
        }
        AuditSearchToken token = tokens.tokenize(domain, normalizedValue);
        if (!token.value().startsWith(domain.prefix() + "_")) {
            throw new IllegalArgumentException("AUDIT_TOKEN_DOMAIN_MISMATCH");
        }
        generated.add(token);
        return token;
    }

    private AuditTokenizationMetadata metadata(List<AuditSearchToken> generated) {
        AuditTokenizationMetadata expected = generated.isEmpty()
                ? tokens.metadata()
                : new AuditTokenizationMetadata(
                        generated.getFirst().profileVersion(), generated.getFirst().keyVersion());
        if (generated.stream().anyMatch(token ->
                !expected.profileVersion().equals(token.profileVersion())
                        || !expected.keyVersion().equals(token.keyVersion()))) {
            throw new IllegalArgumentException("AUDIT_TOKEN_PROFILE_MISMATCH");
        }
        return expected;
    }

    private static String value(AuditSearchToken token) {
        return token == null ? null : token.value();
    }

    private static String digestOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IdentityAccessException unavailable(String code) {
        return new IdentityAccessException(code, "security audit is unavailable");
    }
}
