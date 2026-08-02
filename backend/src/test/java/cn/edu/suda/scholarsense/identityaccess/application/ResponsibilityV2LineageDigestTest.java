package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityV2LineageDigestTest {
    private static final String CANONICAL = """
            RESPONSIBILITY-LINEAGE-DIGEST-1.0.0
            1|019c0000-0000-7000-8000-000000000101|-|1|1|1|14a50ecb1b62bcc138af5bb2590b8a1fcf4dc28f83ee6d6c1c72555ffbff53e6|corrected|SOURCE_CORRECTION|2026-07-31T10:51:41.000000Z""";

    @Test
    void matchesThePublishedCrossImplementationGoldenVector() {
        var event = new ResponsibilityV2LineageDigestEvent(
                1,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000101"),
                null,
                1,
                1,
                1,
                "14a50ecb1b62bcc138af5bb2590b8a1fcf4dc28f83ee6d6c1c72555ffbff53e6",
                "corrected",
                "SOURCE_CORRECTION",
                Instant.parse("2026-07-31T10:51:41Z"));

        assertEquals(
                CANONICAL,
                ResponsibilityV2LineageDigest.canonical(
                        List.of(event)));
        assertEquals(
                "278ca6e199e96ff9ca061d1d471cac31d326af2b46da8c9358383a9db08a1389",
                ResponsibilityV2LineageDigest.digest(
                        List.of(event)));
    }

    @Test
    void rejectsSubMicrosecondTimeThatPostgreSqlCannotRoundTrip() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponsibilityV2LineageDigestEvent(
                        1,
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000101"),
                        null,
                        1,
                        1,
                        1,
                        "a".repeat(64),
                        "corrected",
                        "SOURCE_CORRECTION",
                        Instant.parse(
                                "2026-07-31T10:51:41.000000001Z")));
    }
}
