package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcSensitiveReadTransactionAdapterTest {
    @Test
    void translatesADeferredCommitFailureToTheStableIdentityFailure() {
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) {
                throw new IllegalStateException("raw deferred commit diagnostic");
            }
            @Override public void rollback(TransactionStatus status) {}
        };
        var adapter = new JdbcSensitiveReadTransactionAdapter(new TransactionTemplate(manager));

        IdentityAccessException failure = assertThrows(
                IdentityAccessException.class, () -> adapter.execute(() -> "sensitive-dto"));

        assertEquals("IDENTITY_DEPENDENCY_UNAVAILABLE", failure.code());
    }
}
