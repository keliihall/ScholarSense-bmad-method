package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Establishes a BFF session only after Spring Security has completed protocol/JWS validation. */
public final class OidcSessionEstablishmentService {
    private final IdentitySessionRepository sessions;
    private final TokenCustodyService tokenCustody;
    private final IdentityAuditFactFactory auditFacts;
    private final IdentityAuditPort audit;
    private final IdentityEstablishmentTransactionPort transaction;
    private final PseudonymizationPort pseudonyms;
    private final OpaqueCodeGenerator refreshFamilies;
    private final Clock clock;

    public OidcSessionEstablishmentService(
            IdentitySessionRepository sessions,
            TokenCustodyService tokenCustody,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            IdentityEstablishmentTransactionPort transaction,
            PseudonymizationPort pseudonyms,
            OpaqueCodeGenerator refreshFamilies,
            Clock clock) {
        this.sessions = sessions;
        this.tokenCustody = tokenCustody;
        this.auditFacts = auditFacts;
        this.audit = audit;
        this.transaction = transaction;
        this.pseudonyms = pseudonyms;
        this.refreshFamilies = refreshFamilies;
        this.clock = clock;
    }

    public void establish(OidcSessionEstablishment input) {
        establishAndConsume(input, null, null);
    }

    public ContinuationTarget establishAndConsume(
            OidcSessionEstablishment input,
            ContinuationService continuations,
            String continuationCode) {
        var now = clock.instant();
        String actor = pseudonyms.pseudonymize("identity-actor", input.issuer() + "\0" + input.subject());
        String browser = pseudonyms.pseudonymize("browser-binding", input.browserBinding());
        String sessionPseudonym = "sp_" + ContinuationService.digest(
                pseudonyms.pseudonymize("identity-session", input.sessionId()));
        String refreshDigest = ContinuationService.digest(input.refreshToken());
        String family = refreshFamilies.generate();
        var session = IdentitySession.authenticate(
                input.sessionId(), sessionPseudonym, actor, browser, input.origin(),
                family, refreshDigest, now);
        AtomicReference<ContinuationTarget> continuationTarget = new AtomicReference<>();
        transaction.execute(() -> {
            sessions.save(session);
            tokenCustody.store(
                    input.sessionId(), input.registrationId(), input.accessToken(), input.refreshToken(),
                    input.accessExpiresAt());
            audit.append(auditFacts.create(new IdentityAuditRequest(
                    ActorType.USER,
                    actor,
                    List.of(),
                    new IdentityAuditAuthorizationContext(
                            "not-applicable", null, List.of(), List.of(), "PRE_AUTHENTICATION"),
                    IdentityAuditAction.SESSION_LOGIN,
                    "accepted",
                    "IDENTITY_LOGIN_COMPLETED",
                    "identity-session",
                    sessionPseudonym,
                    "SESSION_ESTABLISHMENT",
                    null,
                    input.sourceIp(),
                    input.traceId(),
                    "identity-session",
                    sessionPseudonym,
                    session.sessionVersion(),
                    null,
                    Map.of(
                            "identitySessionPolicy", "ISP-1.0.0",
                            "hostIntegrationProfile", "HIP-1.0.0"))));
            if (continuationCode != null) {
                if (continuations == null) {
                    throw new IllegalStateException("IDENTITY_CONTINUATION_PORT_UNAVAILABLE");
                }
                continuationTarget.set(continuations.consume(
                        continuationCode, input.browserBinding(), input.origin()));
            }
        });
        return continuationTarget.get();
    }
}
