package cn.edu.suda.scholarsense.rulegovernance.api;

@FunctionalInterface
public interface RuleVersionBusinessOwnerBindingQueryPort {
    RuleVersionBusinessOwnerBindingResult resolve(
            RuleVersionBusinessOwnerBindingQuery query);
}
