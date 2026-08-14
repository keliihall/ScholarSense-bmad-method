package cn.edu.suda.scholarsense.rulegovernance.api;

import java.time.Instant;
import java.util.List;

public record RuleVersionBusinessOwnerBindingQuery(
        List<String> ruleVersionDigests,
        String bindingSetDigest,
        Instant trustedAt,
        String traceId) {
    public RuleVersionBusinessOwnerBindingQuery {
        ruleVersionDigests = List.copyOf(ruleVersionDigests).stream().sorted().toList();
    }
}
