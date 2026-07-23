package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately limited to anonymous fixture records; it is not a ledger deletion port.
 *
 * <p>The implementation must lock and compare the supplied guard version/fencing token against
 * the same persisted guard state used by hold and policy writers, then mutate fixtures in that
 * shared transaction. A check performed through an external callback is not sufficient.
 */
@FunctionalInterface
public interface SyntheticFixtureDestroyPort {
    void destroyOnce(
            UUID executionId,
            long expectedGuardVersion,
            long fencingToken,
            List<String> fixtureRecordIds,
            RetentionGuardSnapshot expectedGuard);
}
