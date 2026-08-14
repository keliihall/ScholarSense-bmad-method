package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityDecision;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTransition;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyDefinition;
import java.util.Objects;

/** A composition result kept bound to the exact RuleVersion definition that produced it. */
public record RuleEligibilityDecision(
        RuleDependencyDefinition rule,
        QualityEligibilityDecision decision,
        QualityEligibilityDecision evaluatedDecision,
        QualityFuseTransition transition) {
    public RuleEligibilityDecision {
        rule = Objects.requireNonNull(rule);
        decision = Objects.requireNonNull(decision);
        evaluatedDecision = Objects.requireNonNull(evaluatedDecision);
        if (transition != null
                && (!transition.applied().equals(decision)
                || !transition.evaluated().equals(evaluatedDecision))) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_TRANSITION_INVALID");
        }
    }

    public RuleEligibilityDecision(
            RuleDependencyDefinition rule,
            QualityEligibilityDecision decision) {
        this(rule, decision, decision, null);
    }

    public static RuleEligibilityDecision applied(
            RuleDependencyDefinition rule,
            QualityFuseTransition transition) {
        return new RuleEligibilityDecision(
                rule, transition.applied(), transition.evaluated(), transition);
    }
}
