package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.auditoperations.application.LedgerVerificationResult;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SpringAuditVerificationTransactionAdapterTest {
    @Test
    void runsTheWholeVerificationInOneRepeatableReadTransaction() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SimpleTransactionStatus());
        SpringAuditVerificationTransactionAdapter adapter =
                new SpringAuditVerificationTransactionAdapter(manager);

        LedgerVerificationResult verified = new LedgerVerificationResult(
                true, 0, "0".repeat(64), Set.of());
        assertEquals(verified, adapter.repeatableRead(() -> verified));

        verify(manager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED
                        && definition.getIsolationLevel()
                            == TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }
}
