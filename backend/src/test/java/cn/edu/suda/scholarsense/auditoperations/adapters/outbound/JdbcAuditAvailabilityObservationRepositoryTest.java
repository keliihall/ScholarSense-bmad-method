package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class JdbcAuditAvailabilityObservationRepositoryTest {

    @Test
    void latestLocksThePersistedAvailabilityStreamBeforeReadingState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        var repository = new JdbcAuditAvailabilityObservationRepository(
                jdbc, new ObjectMapper(), ignored -> java.util.UUID.randomUUID());

        repository.latest();

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).execute(org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"));
        ordered.verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
    }
}
