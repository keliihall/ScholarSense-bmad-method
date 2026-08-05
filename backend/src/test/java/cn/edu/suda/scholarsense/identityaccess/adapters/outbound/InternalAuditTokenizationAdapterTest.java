package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class InternalAuditTokenizationAdapterTest {
    @Test
    void exposesTheIdentityOwnedCurrentKeyWithoutLosingDomainOrMetadata() {
        var adapter = new InternalAuditTokenizationAdapter(
                new HmacIdentityAuditTokenAdapter(
                        new SecretKeySpec(new byte[32], "HmacSHA256"), "k7"));

        var firstSession = adapter.tokenize(
                AuditTokenizationDomain.ACTOR, "stable-actor-pseudonym");
        var secondSession = adapter.tokenize(
                AuditTokenizationDomain.ACTOR, "stable-actor-pseudonym");
        var object = adapter.tokenize(
                AuditTokenizationDomain.OBJECT, "stable-actor-pseudonym");

        assertEquals(firstSession, secondSession);
        assertNotEquals(firstSession.value(), object.value());
        assertTrue(firstSession.value().startsWith("ast_v1_k7_"));
        assertEquals("AUDIT-TOKENIZATION-1.0.0", firstSession.profileVersion());
        assertEquals("k7", firstSession.keyVersion());
    }
}
