package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuthorizationDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompositeCatalogAuthorizationAdapterTest {
    @Test
    void appliesDistinctRfpActionsToSourcesAndDependencies() {
        List<CompositeAuthorizationRequest> requests = new ArrayList<>();
        CompositeAuthorizationPort port = request -> {
            requests.add(request);
            CompositeAuthorizationDecision decision = mock(CompositeAuthorizationDecision.class);
            when(decision.outcome()).thenReturn(CompositeAuthorizationOutcome.ALLOW);
            return decision;
        };
        var adapter = new CompositeCatalogAuthorizationAdapter(port);

        CatalogAuthorizationDecision decision = adapter.authorize(
                "owner-r6", "data-quality.repair", "data-quality.reconcile",
                CatalogFixtures.draft(
                        UUID.fromString("019fc6b8-9400-7000-8000-000000000041"),
                        Instant.parse("2026-08-05T00:00:00Z")),
                "00112233445566778899aabbccddeeff");

        assertEquals(CatalogAuthorizationDecision.ALLOW, decision);
        assertEquals(17, requests.stream().filter(item -> item.objectClass().equals("SOURCE")).count());
        assertEquals(11, requests.stream().filter(item -> item.objectClass().equals("DEPENDENCY")).count());
        assertEquals(List.of("data-quality.repair"), requests.stream()
                .filter(item -> item.objectClass().equals("SOURCE"))
                .map(CompositeAuthorizationRequest::actionId).distinct().toList());
        assertEquals(List.of("data-quality.reconcile"), requests.stream()
                .filter(item -> item.objectClass().equals("DEPENDENCY"))
                .map(CompositeAuthorizationRequest::actionId).distinct().toList());
    }

    @Test
    void mapsThrowingMandatoryAuthorizationDependenciesToUnavailable() {
        CompositeAuthorizationPort port = request -> {
            throw new IllegalStateException("mandatory decision audit unavailable");
        };

        CatalogAuthorizationDecision decision = new CompositeCatalogAuthorizationAdapter(port)
                .authorize(
                        "owner-r6", "data-quality.read", "data-quality.read",
                        CatalogFixtures.draft(
                                UUID.fromString("019fc6b8-9400-7000-8000-000000000042"),
                                Instant.parse("2026-08-05T00:00:00Z")),
                        "00112233445566778899aabbccddeeff");

        assertEquals(CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE, decision);
    }
}
