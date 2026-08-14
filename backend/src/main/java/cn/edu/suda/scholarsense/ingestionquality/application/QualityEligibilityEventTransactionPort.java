package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.function.Function;

/**
 * Executes inbox, cursor, pending pair, state, eligibility, audit and outbox planning in one
 * owner-local transaction. The PostgreSQL adapter persists the returned mutation atomically.
 */
public interface QualityEligibilityEventTransactionPort {
    QualityEligibilityMutation transact(
            UpstreamQualityEvent event,
            Function<QualityEligibilityProcessingState, QualityEligibilityMutation> planner);
}
