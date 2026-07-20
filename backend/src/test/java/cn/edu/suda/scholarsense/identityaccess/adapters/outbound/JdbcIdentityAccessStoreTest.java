package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcIdentityAccessStoreTest {
    @Test
    void confirmingRemoteLogoutAtomicallyPublishesTheAccountSwitchAuthorizationAction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new JdbcIdentityAccessStore(jdbc);

        store.markConfirmed(
                "018f7b87-ee53-7942-9aec-d5948b86b811",
                Instant.parse("2026-07-20T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        String statement = sql.getValue();
        assertTrue(statement.contains("result_remote_pending=false"));
        assertTrue(statement.contains("'/oauth2/authorization/' || confirmed.registration_id"));
        assertTrue(statement.contains("result.idempotency_key=confirmed.idempotency_key"));
    }
}
