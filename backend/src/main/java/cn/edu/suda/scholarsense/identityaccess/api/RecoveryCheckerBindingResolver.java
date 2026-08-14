package cn.edu.suda.scholarsense.identityaccess.api;

import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingQuery;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingQueryPort;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingResult;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resolves the exact all-distinct current business-owner checker set or fails closed. */
public final class RecoveryCheckerBindingResolver {
    private final RuleVersionBusinessOwnerBindingQueryPort owners;
    private final CurrentNaturalPersonBindingQueryPort persons;

    public RecoveryCheckerBindingResolver(
            RuleVersionBusinessOwnerBindingQueryPort owners,
            CurrentNaturalPersonBindingQueryPort persons) {
        this.owners = Objects.requireNonNull(owners);
        this.persons = Objects.requireNonNull(persons);
    }

    public Resolution resolve(
            List<String> ruleVersionDigests,
            String expectedOwnerBindingSetDigest,
            Instant trustedAt,
            String traceId) {
        List<String> expectedRules = List.copyOf(ruleVersionDigests).stream().sorted().toList();
        if (expectedRules.isEmpty() || expectedRules.stream().distinct().count()
                != expectedRules.size()) return Resolution.unavailable("CHECKER_RULE_SET_INVALID");
        RuleVersionBusinessOwnerBindingResult ownerResult;
        try {
            ownerResult = owners.resolve(new RuleVersionBusinessOwnerBindingQuery(
                    expectedRules, expectedOwnerBindingSetDigest, trustedAt, traceId));
        } catch (RuntimeException unavailable) {
            return Resolution.unavailable("CHECKER_OWNER_PROVIDER_UNAVAILABLE");
        }
        if (ownerResult == null
                || ownerResult.availability()
                    != RuleVersionBusinessOwnerBindingResult.Availability.AVAILABLE
                || expectedOwnerBindingSetDigest != null
                        && !expectedOwnerBindingSetDigest.equals(ownerResult.bindingSetDigest())
                || !ownerResult.bindings().stream().map(value -> value.ruleVersionDigest())
                        .sorted().toList().equals(expectedRules)
                || ownerResult.bindings().stream().anyMatch(value ->
                        !active(trustedAt, value.effectiveFrom(), value.effectiveTo()))) {
            return Resolution.unavailable("CHECKER_OWNER_BINDING_UNAVAILABLE");
        }
        List<String> ownerKeys = ownerResult.bindings().stream()
                .map(value -> value.businessOwnerKeyDigest()).distinct().sorted().toList();
        CurrentNaturalPersonBindingResult personResult;
        try {
            personResult = persons.resolve(new CurrentNaturalPersonBindingQuery(
                    ownerKeys, trustedAt, traceId));
        } catch (RuntimeException unavailable) {
            return Resolution.unavailable("CHECKER_PERSON_PROVIDER_UNAVAILABLE");
        }
        if (personResult == null
                || personResult.availability()
                    != CurrentNaturalPersonBindingResult.Availability.AVAILABLE
                || personResult.bindings().size() != ownerKeys.size()
                || !personResult.bindings().stream().map(value -> value.businessOwnerKeyDigest())
                        .sorted().toList().equals(ownerKeys)
                || personResult.bindings().stream().anyMatch(value ->
                        !active(trustedAt, value.effectiveFrom(), value.effectiveTo()))) {
            return Resolution.unavailable("CHECKER_PERSON_BINDING_UNAVAILABLE");
        }
        List<String> principals = personResult.bindings().stream()
                .map(value -> value.naturalPersonPrincipalDigest()).sorted().toList();
        if (principals.isEmpty() || principals.stream().distinct().count() != principals.size()) {
            return Resolution.unavailable("CHECKER_PERSON_BINDING_AMBIGUOUS");
        }
        return new Resolution(
                true, principals, ownerResult.bindingSetDigest(),
                personResult.bindingSetDigest(),
                digest(String.join("\n", ownerResult.bindingSetDigest(),
                        personResult.bindingSetDigest())), null);
    }

    private static boolean active(Instant now, Instant from, Instant to) {
        return now != null && from != null && !now.isBefore(from)
                && (to == null || now.isBefore(to));
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    public record Resolution(
            boolean available,
            List<String> checkerPrincipalDigests,
            String ownerBindingSetDigest,
            String naturalPersonBindingSetDigest,
            String checkerSetDigest,
            String reasonCode) {
        public Resolution {
            checkerPrincipalDigests = List.copyOf(checkerPrincipalDigests).stream()
                    .sorted(Comparator.naturalOrder()).toList();
        }
        static Resolution unavailable(String reason) {
            return new Resolution(false, List.of(), null, null, null, reason);
        }
    }
}
