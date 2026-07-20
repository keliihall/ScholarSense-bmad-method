package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class HmacIdentityAuditTokenAdapterTest {
    @Test
    void usesKeyedVersionedDomainSeparatedDeterministicTokens() {
        var adapter = new HmacIdentityAuditTokenAdapter(
                new SecretKeySpec(new byte[32], "HmacSHA256"), "k7");

        var actor = adapter.tokenize(AuditTokenDomain.ACTOR, " normalized-user ");
        var retry = adapter.tokenize(AuditTokenDomain.ACTOR, "normalized-user");
        var object = adapter.tokenize(AuditTokenDomain.OBJECT, "normalized-user");

        assertEquals(actor, retry);
        assertNotEquals(actor.value(), object.value());
        assertEquals("AUDIT-TOKENIZATION-1.0.0", actor.profileVersion());
        assertEquals("k7", actor.keyVersion());
        assertFalse(actor.value().contains("normalized-user"));
    }
}
