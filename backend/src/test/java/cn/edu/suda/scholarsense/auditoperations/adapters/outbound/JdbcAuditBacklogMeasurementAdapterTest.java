package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogSnapshot;
import cn.edu.suda.scholarsense.auditoperations.application.AuditBacklogMeasurement;
import cn.edu.suda.scholarsense.auditoperations.application.FindingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAuditBacklogMeasurementAdapterTest {
    @Test
    void resolvedPermanentFailuresRemainStoredButNoLongerCountAsActiveBacklog() {
        var producer = new AuditProducerBacklogSnapshot(1, 3_600, 0, 0, true, NOW, true);
        FindingRepository findings = mock(FindingRepository.class);
        when(findings.hasActivePermanentFinding()).thenReturn(false);
        JdbcTemplate jdbc = healthyVerificationJdbc();
        JdbcAuditBacklogMeasurementAdapter adapter = new JdbcAuditBacklogMeasurementAdapter(
                () -> producer, findings, jdbc);

        AuditBacklogMeasurement measurement = adapter.current();

        assertEquals(0, measurement.unconfirmedCount());
        assertEquals(0, measurement.oldestUnconfirmedAgeSeconds());
        assertFalse(measurement.permanentFindingActive());
    }

    private static JdbcTemplate healthyVerificationJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenReturn(List.of(true));
        return jdbc;
    }
}
