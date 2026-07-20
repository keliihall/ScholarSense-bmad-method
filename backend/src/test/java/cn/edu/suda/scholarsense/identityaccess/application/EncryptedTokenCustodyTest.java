package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncryptedTokenCustodyTest {

    @Test
    void storesOnlyEnvelopeEncryptedMaterialWithExternalKeyMetadata() {
        List<EncryptedAuthorizedClient> stored = new ArrayList<>();
        EnvelopeEncryptionPort encryption = (plaintext, purpose) -> new EncryptedSecret(
                "ciphertext".getBytes(StandardCharsets.UTF_8), "wrapped-dek".getBytes(StandardCharsets.UTF_8),
                "kms://stage/identity-tokens", "v7", "nonce".getBytes(StandardCharsets.UTF_8));
        AuthorizedClientSecretRepository repository = stored::add;
        TokenCustodyService service = new TokenCustodyService(encryption, repository);

        service.store("session-1", "school-idp", "access-plaintext", "refresh-plaintext",
                Instant.parse("2026-07-20T00:05:00Z"));

        assertEquals(1, stored.size());
        String rendered = stored.getFirst().toString();
        assertFalse(rendered.contains("access-plaintext"));
        assertFalse(rendered.contains("refresh-plaintext"));
        assertEquals("kms://stage/identity-tokens", stored.getFirst().accessToken().keyRef());
        assertEquals("v7", stored.getFirst().refreshToken().keyVersion());
    }
}
