package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccessInvalidationEventJsonCodecTest {
    private static final Instant NOW =
            Instant.parse("2026-07-31T12:00:00Z");
    private static final AccessInvalidationLineageId LINEAGE =
            new AccessInvalidationLineageId(
                    "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @Test
    void validatesTheCompleteSemanticEnvelopeAndHashesCanonicalWirePayload() {
        var codec = new AccessInvalidationEventJsonCodec(
                new ObjectMapper());
        AccessInvalidationFact fact = fact();
        String payload = codec.encode(fact);

        var validated = codec.validate(fact, payload);

        assertEquals(payload, validated.canonicalPayload());
        assertEquals(64, validated.payloadDigest().length());
        assertNotEquals(fact.payloadDigest(), validated.payloadDigest());
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.validate(
                        fact,
                        payload.replace(
                                "\"producer\":\"identity-access\"",
                                "\"producer\":\"tampered\"")));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.validate(
                        fact,
                        "{\"id\":\"" + fact.eventId()
                                + "\",\"data\":{\"eventId\":\""
                                + fact.eventId() + "\"}}"));
    }

    private static AccessInvalidationFact fact() {
        return new AccessInvalidationFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000801"),
                "0123456789abcdef0123456789abcdef",
                AccessInvalidationChangeKind.REVOKED,
                AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                LINEAGE,
                null,
                null,
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE.value(),
                1,
                1,
                NOW,
                new AccessInvalidationSourceVector(
                        "SRC-P0-RESPONSIBILITY-001",
                        8,
                        8,
                        List.of(new AccessInvalidationDependencyWatermark(
                                "responsibility-authority",
                                "sandbox-0",
                                8))),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + "a".repeat(40),
                        "scptok_" + "b".repeat(40),
                        "c".repeat(64),
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        AccessInvalidationAuthorizationState.INVALIDATED,
                        true,
                        true,
                        true,
                        false,
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        NOW.plus(365, ChronoUnit.DAYS),
                        false),
                "d".repeat(64));
    }
}
