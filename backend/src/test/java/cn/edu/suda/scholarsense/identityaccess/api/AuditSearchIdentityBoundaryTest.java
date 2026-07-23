package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HistoricalAuditSearchTokenAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacIdentityAuditTokenAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.RfpAuditSearchConformanceAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AuditSearchIdentityBoundaryTest {
    @Test
    void conformanceOracleSeparatesR3AndR7AndUsesDenyOverrides() {
        var adapter = RfpAuditSearchConformanceAdapter.approvedFixture();

        var business = adapter.authorize(request("r3", AuditSearchView.BUSINESS));
        assertTrue(business.allowed());
        assertEquals("audit.search-business-metadata", business.action());
        assertEquals(FieldVisibility.MASKED, business.fieldProjection().get("I"));
        assertEquals(FieldVisibility.MASKED, business.fieldProjection().get("T"));

        var technical = adapter.authorize(request("r7", AuditSearchView.TECHNICAL));
        assertTrue(technical.allowed());
        assertEquals("audit.search-technical-metadata", technical.action());
        assertEquals(FieldVisibility.HIDDEN, technical.fieldProjection().get("I"));
        assertEquals(FieldVisibility.CLEAR, technical.fieldProjection().get("T"));

        assertFalse(adapter.authorize(request("r3+r7", AuditSearchView.BUSINESS)).allowed());
        assertFalse(adapter.authorize(request("other", AuditSearchView.BUSINESS)).allowed());
        assertFalse(adapter.authorize(request("revoked-r3", AuditSearchView.BUSINESS)).allowed());
        assertFalse(adapter.authorize(request("policy-unavailable", AuditSearchView.BUSINESS)).allowed());
    }

    @Test
    void productionBindingIsAlwaysClosedUntilAuthoritativeRolesExist() {
        AuditSearchAuthorizationPort production = AuditSearchAuthorizationPort.productionFailClosed();

        var decision = production.authorize(request("r3", AuditSearchView.BUSINESS));
        assertFalse(decision.allowed());
        assertEquals("AUDIT_SEARCH_AUTHORITY_UNAVAILABLE", decision.reasonCode());
        assertEquals(AuditSearchCapabilityManifest.conformanceOnly(), production.capabilityManifest());
        assertTrue(production.capabilityManifest().conformanceVerified());
        assertFalse(production.capabilityManifest().productionAuthorizationEnabled());
    }

    @Test
    void historicalTokenQueryUsesEveryRetainedKeyWithNfkcAndDomainSeparation() {
        var tokens = new HistoricalAuditSearchTokenAdapter(
                "AUDIT-TOKENIZATION-1.0.0",
                Map.of(
                        "k7", new HmacIdentityAuditTokenAdapter(key((byte) 7), "k7"),
                        "k8", new HmacIdentityAuditTokenAdapter(key((byte) 8), "k8")),
                List.of("k7", "k8"));

        var fullWidth = tokens.query(new AuditSearchTokenQuery(AuditSearchTokenDomain.ACTOR, "Ａ１２３", Instant.EPOCH));
        var normalized = tokens.query(new AuditSearchTokenQuery(AuditSearchTokenDomain.ACTOR, "A123", Instant.EPOCH));
        var object = tokens.query(new AuditSearchTokenQuery(AuditSearchTokenDomain.OBJECT, "A123", Instant.EPOCH));

        assertEquals(List.of("k7", "k8"), fullWidth.stream().map(AuditSearchQueryToken::keyVersion).toList());
        assertEquals(fullWidth, normalized);
        assertFalse(fullWidth.getFirst().value().contains("A123"));
        assertFalse(fullWidth.getFirst().value().equals(object.getFirst().value()));
    }

    @Test
    void missingHistoricalKeyAndKmsFailureFailClosedWithoutEchoingInput() {
        var missing = new HistoricalAuditSearchTokenAdapter(
                "AUDIT-TOKENIZATION-1.0.0",
                Map.of("k8", new HmacIdentityAuditTokenAdapter(key((byte) 8), "k8")),
                List.of("k7", "k8"));
        var missingFailure = assertThrows(IllegalStateException.class,
                () -> missing.query(new AuditSearchTokenQuery(AuditSearchTokenDomain.ACTOR, "raw-sensitive-id", Instant.EPOCH)));
        assertEquals("AUDIT_SEARCH_TOKEN_KEY_UNAVAILABLE", missingFailure.getMessage());
        assertFalse(missingFailure.toString().contains("raw-sensitive-id"));

        var unavailable = new HistoricalAuditSearchTokenAdapter(
                "AUDIT-TOKENIZATION-1.0.0", Map.of("k7", (domain, value) -> {
                    throw new IllegalStateException("AUDIT_TOKENIZATION_UNAVAILABLE");
                }), List.of("k7"));
        var kmsFailure = assertThrows(IllegalStateException.class,
                () -> unavailable.query(new AuditSearchTokenQuery(AuditSearchTokenDomain.ACTOR, "raw-sensitive-id", Instant.EPOCH)));
        assertEquals("AUDIT_SEARCH_TOKENIZATION_UNAVAILABLE", kmsFailure.getMessage());
        assertFalse(kmsFailure.toString().contains("raw-sensitive-id"));
    }

    private static AuditSearchAuthorizationRequest request(String session, AuditSearchView view) {
        return new AuditSearchAuthorizationRequest(session, view, "fixture-record", "fixture-scope", "trace-test-001");
    }

    private static SecretKeySpec key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
