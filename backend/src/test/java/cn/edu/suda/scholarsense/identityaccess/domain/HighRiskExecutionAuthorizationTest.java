package cn.edu.suda.scholarsense.identityaccess.domain;

import static cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalLifecycleTest.binding;
import static cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalLifecycleTest.digest;
import static cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalLifecycleTest.uuid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HighRiskExecutionAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00.123456Z");

    @Test
    void tokenAndLeaseUseHalfOpenFifteenMinuteBoundary() {
        HighRiskExecutionAuthorizationLease lease = lease();
        lease.reserve(NOW.plusSeconds(899));
        assertEquals(HighRiskExecutionLeaseState.RESERVED, lease.state());

        HighRiskExecutionAuthorizationLease expired = lease();
        expired.expire(NOW.plusSeconds(900));
        assertEquals(HighRiskExecutionLeaseState.EXPIRED, expired.state());
        assertThrows(IllegalStateException.class, () -> expired.reserve(NOW.plusSeconds(900)));
    }

    @Test
    void lateConfirmationExecutesOnlyWhenOwnerCommittedBeforeAuthorizationDeadline() {
        HighRiskExecutionAuthorizationLease lease = lease();
        lease.reserve(NOW.plusSeconds(1));
        lease.expire(NOW.plusSeconds(900));
        lease.confirmOwnerCommit(
                "commit:recovery:0001", NOW.plusSeconds(899), digest('a'),
                uuid("019ff5a0-3000-7000-8000-000000000106"), NOW.plusSeconds(1000));
        assertEquals(HighRiskExecutionLeaseState.EXECUTED, lease.state());

        HighRiskExecutionAuthorizationLease tooLate = lease();
        tooLate.reserve(NOW.plusSeconds(1));
        tooLate.expire(NOW.plusSeconds(900));
        assertThrows(IllegalStateException.class, () -> tooLate.confirmOwnerCommit(
                "commit:recovery:0002", NOW.plusSeconds(900), digest('a'),
                uuid("019ff5a0-3000-7000-8000-000000000107"), NOW.plusSeconds(1000)));
    }

    private static HighRiskExecutionAuthorizationLease lease() {
        HighRiskExecutionToken token = new HighRiskExecutionToken(
                uuid("019ff5a0-3000-7000-8000-000000000103"),
                uuid("019ff5a0-3000-7000-8000-000000000101"), 3, digest('f'), binding(),
                NOW, NOW.plusSeconds(900), "ingestion-quality-recovery-worker", "hrap-k1",
                "A".repeat(43));
        return HighRiskExecutionAuthorizationLease.issued(
                uuid("019ff5a0-3000-7000-8000-000000000104"), digest('e'),
                uuid("019ff5a0-3000-7000-8000-000000000105"), token,
                "hrap-k1", "B".repeat(43));
    }
}
