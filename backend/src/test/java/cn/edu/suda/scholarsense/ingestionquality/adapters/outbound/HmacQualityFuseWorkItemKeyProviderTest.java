package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class HmacQualityFuseWorkItemKeyProviderTest {
    @Test
    void derivesDomainSeparatedOpaqueIdentityWithPinnedKeyVersion() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 7);
        HmacQualityFuseWorkItemKeyProvider provider =
                new HmacQualityFuseWorkItemKeyProvider(
                        new SecretKeySpec(bytes, "HmacSHA256"), "k7");

        var identity = provider.current(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", 1);

        assertEquals("k7", identity.keyVersion());
        assertEquals(
                "qf:aec2bc46dd20bf0a4153ef1005035dfd9819f59171daf50169ae534de59f693d",
                identity.workItemKey());
    }

    @Test
    void rotationChangesOnlyIdentitiesCreatedWithTheNewProvider() {
        byte[] first = new byte[32];
        byte[] second = new byte[32];
        Arrays.fill(first, (byte) 1);
        Arrays.fill(second, (byte) 2);
        var oldProvider = new HmacQualityFuseWorkItemKeyProvider(
                new SecretKeySpec(first, "HmacSHA256"), "k1");
        var newProvider = new HmacQualityFuseWorkItemKeyProvider(
                new SecretKeySpec(second, "HmacSHA256"), "k2");

        var oldIdentity = oldProvider.current(
                "SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001", 3);
        var newIdentity = newProvider.current(
                "SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001", 3);

        assertEquals("k1", oldIdentity.keyVersion());
        assertEquals("k2", newIdentity.keyVersion());
        assertNotEquals(oldIdentity.workItemKey(), newIdentity.workItemKey());
    }
}
