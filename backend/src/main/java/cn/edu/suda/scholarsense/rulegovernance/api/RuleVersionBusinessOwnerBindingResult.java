package cn.edu.suda.scholarsense.rulegovernance.api;

import java.util.Comparator;
import java.util.List;

public record RuleVersionBusinessOwnerBindingResult(
        Availability availability,
        List<RuleVersionBusinessOwnerBinding> bindings,
        String bindingSetDigest,
        String reasonCode,
        String traceId) {
    public enum Availability { AVAILABLE, UNAVAILABLE, NOT_INSTALLED }
    public RuleVersionBusinessOwnerBindingResult {
        bindings = List.copyOf(bindings).stream()
                .sorted(Comparator.comparing(
                        RuleVersionBusinessOwnerBinding::ruleVersionDigest)).toList();
    }
}
