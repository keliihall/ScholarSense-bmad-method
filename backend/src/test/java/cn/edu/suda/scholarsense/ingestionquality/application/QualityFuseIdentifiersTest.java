package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityFuseIdentifiersTest {
    @Test
    void derivationMatchesFrozenPostgreSqlUuidV7Function() {
        UUID result = QualityFuseIdentifiers.derive(
                UUID.fromString("019d2c7d-4000-7000-8001-000000000401"),
                "quality-fuse-episode:SRC-P0-CAMPUS-ACCESS-001@"
                        + "DEP-P0-CAMPUS-ACCESS-001:1");

        assertEquals(UUID.fromString("019d2c7d-4000-7c34-8562-a08549798a88"), result);
        assertEquals(7, result.version());
        assertEquals(2, result.variant());
    }
}
