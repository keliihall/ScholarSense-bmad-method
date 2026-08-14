package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBinding;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingResult;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryCheckerBindingResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00.123456Z");
    private static final String TRACE = "00112233445566778899aabbccddeeff";

    @Test
    void resolvesOneCurrentNaturalPersonPerDistinctBusinessOwner() {
        var resolver = new RecoveryCheckerBindingResolver(
                query -> new RuleVersionBusinessOwnerBindingResult(
                        RuleVersionBusinessOwnerBindingResult.Availability.AVAILABLE,
                        List.of(owner(digest('1'), digest('a')), owner(digest('2'), digest('b'))),
                        digest('c'), null, TRACE),
                query -> new CurrentNaturalPersonBindingResult(
                        CurrentNaturalPersonBindingResult.Availability.AVAILABLE,
                        List.of(person(digest('a'), digest('e')), person(digest('b'), digest('f'))),
                        digest('d'), null, TRACE));

        var result = resolver.resolve(
                List.of(digest('2'), digest('1')), digest('c'), NOW, TRACE);
        assertTrue(result.available());
        assertEquals(List.of(digest('e'), digest('f')), result.checkerPrincipalDigests());
        assertEquals(digest('c'), result.ownerBindingSetDigest());
        assertEquals(digest('d'), result.naturalPersonBindingSetDigest());
        assertEquals(sha256(digest('c') + "\n" + digest('d')),
                result.checkerSetDigest());
    }

    @Test
    void missingDuplicateOrUnavailableBindingFailsClosed() {
        var duplicatePerson = new RecoveryCheckerBindingResolver(
                query -> new RuleVersionBusinessOwnerBindingResult(
                        RuleVersionBusinessOwnerBindingResult.Availability.AVAILABLE,
                        List.of(owner(digest('1'), digest('a')), owner(digest('2'), digest('b'))),
                        digest('c'), null, TRACE),
                query -> new CurrentNaturalPersonBindingResult(
                        CurrentNaturalPersonBindingResult.Availability.AVAILABLE,
                        List.of(person(digest('a'), digest('e')), person(digest('b'), digest('e'))),
                        digest('d'), null, TRACE));
        assertFalse(duplicatePerson.resolve(
                List.of(digest('1'), digest('2')), digest('c'), NOW, TRACE).available());

        var unavailable = new RecoveryCheckerBindingResolver(
                query -> { throw new IllegalStateException("offline"); },
                query -> { throw new AssertionError("must not call identity"); });
        assertFalse(unavailable.resolve(
                List.of(digest('1')), digest('c'), NOW, TRACE).available());
    }

    private static RuleVersionBusinessOwnerBinding owner(String rule, String key) {
        return new RuleVersionBusinessOwnerBinding(rule, key, 1, NOW.minusSeconds(1), null);
    }
    private static CurrentNaturalPersonBinding person(String key, String principal) {
        return new CurrentNaturalPersonBinding(key, principal, 1, NOW.minusSeconds(1), null);
    }
    private static String digest(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
