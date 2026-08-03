package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompositeAuthorizationContractTest {
    @Test
    void requestIsTransportNeutralAndRejectsUnexpandedActions() {
        var request = new CompositeAuthorizationRequest(
                "actor-pseudonym",
                "CANDIDATE",
                "care.read",
                "a".repeat(64),
                7,
                Optional.of("b".repeat(64)),
                Optional.of("lin_responsibility_case_a_000000000000"),
                "0".repeat(32));

        assertEquals("CANDIDATE", request.objectClass());
        assertThrows(IllegalArgumentException.class, () -> new CompositeAuthorizationRequest(
                "actor-pseudonym",
                "CANDIDATE",
                "care.read/candidate.review",
                "a".repeat(64),
                7,
                Optional.empty(),
                Optional.empty(),
                "0".repeat(32)));
    }

    @Test
    void missingOwnerBindingIsExplicitlyNotInstalledAndFailClosed() {
        var evidence = AuthorizationObjectEvidence.notInstalled();

        assertEquals(AuthorizationEvidenceAvailability.NOT_INSTALLED, evidence.availability());
        assertEquals(evidence, AuthorizationObjectEvidenceQueryPort.notInstalled().resolve(
                new AuthorizationObjectEvidenceQuery(
                        "actor-pseudonym",
                        TestIds.ACCOUNT,
                        java.util.Set.of(TestIds.COLLEGE),
                        "CANDIDATE",
                        "care.read",
                        "a".repeat(64),
                        1,
                        java.time.Instant.parse("2026-08-01T00:00:00Z"))));
    }

    private static final class TestIds {
        private static final java.util.UUID ACCOUNT =
                java.util.UUID.fromString("019c1234-0000-7000-8000-000000000701");
        private static final java.util.UUID COLLEGE =
                java.util.UUID.fromString("019c1234-0000-7000-8000-000000000702");
    }
}
