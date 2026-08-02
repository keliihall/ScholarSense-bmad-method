package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

    @Test
    void mapsV1CurrentWithoutInventingV2AccessMetadata() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("relation_id", UUID.class)).thenReturn(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000701"));
        when(rs.getString("source_id"))
                .thenReturn("SRC-P0-RESPONSIBILITY-001");
        when(rs.getString("relation_ref_token"))
                .thenReturn("rtok_" + "a".repeat(40));
        when(rs.getString("student_ref_purpose"))
                .thenReturn("RESPONSIBILITY-STUDENT-REF");
        when(rs.getString("student_ref_key_version"))
                .thenReturn("resp-student-v1");
        when(rs.getString("student_ref_token"))
                .thenReturn("stok_" + "b".repeat(40));
        when(rs.getString("student_ref_digest"))
                .thenReturn("c".repeat(64));
        when(rs.getString("student_equivalence_digest"))
                .thenReturn("d".repeat(64));
        when(rs.getString("counselor_account_ref_digest"))
                .thenReturn("e".repeat(64));
        when(rs.getString("college_organization_ref_digest"))
                .thenReturn("f".repeat(64));
        when(rs.getString("responsibility_type")).thenReturn("primary");
        when(rs.getString("relation_status")).thenReturn("active");
        when(rs.getTimestamp("effective_from")).thenReturn(
                Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")));
        when(rs.getLong("source_version")).thenReturn(7L);
        when(rs.getLong("source_watermark")).thenReturn(7L);
        when(rs.getLong("record_version")).thenReturn(1L);
        when(rs.getLong("aggregate_version")).thenReturn(1L);
        when(rs.getString("payload_digest"))
                .thenReturn("1".repeat(64));

        var relation = JdbcResponsibilitySyncRepository.mapRelation(rs);

        assertFalse(relation.hasInvalidationMetadata());
        assertEquals("resp-student-v1",
                relation.studentSourceReference().keyVersion());
    }
}
