package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JdbcResponsibilitySyncRepositoryTest {
    @Test
    void resolvedExceptionReopensAsOpenedForAuditTransition() {
        assertEquals(
                "opened",
                JdbcResponsibilitySyncRepository
                        .exceptionEventType("resolved"));
        assertEquals(
                "updated",
                JdbcResponsibilitySyncRepository
                        .exceptionEventType("open"));
        assertEquals(
                "opened",
                JdbcResponsibilityReconciliationAdapter
                        .reconciliationExceptionEventType(
                                "resolved"));
        assertEquals(
                "updated",
                JdbcResponsibilityReconciliationAdapter
                        .reconciliationExceptionEventType("open"));
    }
}
