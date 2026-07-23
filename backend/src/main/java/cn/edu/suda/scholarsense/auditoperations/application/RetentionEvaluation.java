package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.List;

public record RetentionEvaluation(List<String> eligibleIds, List<String> heldIds, List<String> notDueIds) {
    public RetentionEvaluation {
        eligibleIds = List.copyOf(eligibleIds);
        heldIds = List.copyOf(heldIds);
        notDueIds = List.copyOf(notDueIds);
    }
}
