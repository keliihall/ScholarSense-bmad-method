package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompositeAuthorizationObjectEvidenceQueryPortTest {
    private static final UUID ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000701");

    @Test
    void routesEachObjectClassToItsSingleOwningProvider() {
        AuthorizationObjectEvidence sourceEvidence = available(11);
        AuthorizationObjectEvidence reportEvidence = available(12);
        var source = AuthorizationObjectEvidenceProvider.forObjectClasses(
                Set.of("SOURCE", "DEPENDENCY"), ignored -> sourceEvidence);
        var audit = AuthorizationObjectEvidenceProvider.forObjectClasses(
                Set.of("AGGREGATE_REPORT", "TELEMETRY"), ignored -> reportEvidence);
        var composite = new CompositeAuthorizationObjectEvidenceQueryPort(
                List.of(source, audit));

        assertEquals(sourceEvidence, composite.resolve(query("SOURCE")));
        assertEquals(reportEvidence, composite.resolve(query("AGGREGATE_REPORT")));
    }

    @Test
    void duplicateOwnersAndProviderFailuresAreUnavailableWhileUnknownClassesAreNotInstalled() {
        var first = AuthorizationObjectEvidenceProvider.forObjectClasses(
                Set.of("SOURCE"), ignored -> available(1));
        var duplicate = AuthorizationObjectEvidenceProvider.forObjectClasses(
                Set.of("SOURCE"), ignored -> available(2));
        var throwing = AuthorizationObjectEvidenceProvider.forObjectClasses(
                Set.of("DEPENDENCY"), ignored -> {
                    throw new IllegalStateException("do not leak");
                });
        var composite = new CompositeAuthorizationObjectEvidenceQueryPort(
                List.of(first, duplicate, throwing));

        assertEquals(
                AuthorizationEvidenceAvailability.UNAVAILABLE,
                composite.resolve(query("SOURCE")).availability());
        assertEquals(
                AuthorizationEvidenceAvailability.UNAVAILABLE,
                composite.resolve(query("DEPENDENCY")).availability());
        assertEquals(
                AuthorizationEvidenceAvailability.NOT_INSTALLED,
                composite.resolve(query("CANDIDATE")).availability());
    }

    private static AuthorizationObjectEvidenceQuery query(String objectClass) {
        return new AuthorizationObjectEvidenceQuery(
                "actor-pseudonym",
                ACCOUNT,
                Set.of(),
                objectClass,
                "data-quality.read",
                "a".repeat(64),
                7,
                Instant.parse("2026-08-05T00:00:00Z"));
    }

    private static AuthorizationObjectEvidence available(long relationVersion) {
        return new AuthorizationObjectEvidence(
                AuthorizationEvidenceAvailability.AVAILABLE,
                Set.of(),
                "test-purpose",
                Set.of(),
                null,
                null,
                Set.of(),
                false,
                relationVersion,
                0,
                0,
                7,
                Optional.empty());
    }
}
