package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityAuditVocabularyTest {
    @Test
    void acceptsRegisteredIdentityCodesAndRejectsSensitiveValuesThatMerelyMatchTheShape() {
        assertDoesNotThrow(() -> IdentityAuditVocabulary.validate(request("SESSION_CONTINUITY")));
        assertThrows(IllegalArgumentException.class,
                () -> IdentityAuditVocabulary.validate(request("STUDENT_2026000001")));
        assertThrows(IllegalArgumentException.class, () -> new IdentityAuditAuthorizationContext(
                "allow", "ISP-1.0.0", List.of("STUDENT_2026000001"), List.of(), null));
    }

    private static IdentityAuditRequest request(String purpose) {
        return new IdentityAuditRequest(
                ActorType.USER, "actor", List.of(),
                new IdentityAuditAuthorizationContext(
                        "allow", "ISP-1.0.0", List.of("CURRENT_SESSION"), List.of(), null),
                IdentityAuditAction.SESSION_VIEW, "accepted", "AUTHORIZATION_ALLOWED",
                "identity-session", "session", purpose, "CURRENT_SESSION", "192.0.2.1",
                "0123456789abcdef0123456789abcdef", "identity-session", "session", 1L,
                null, Map.of("identitySessionPolicy", "ISP-1.0.0"));
    }
}
