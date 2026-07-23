package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JdbcAuditSearchProjectionWriterTest {
    @Test
    void roleSummaryIsBoundedForEveryContractValidRoleSet() {
        List<String> roles = IntStream.range(0, 100)
                .mapToObj(index -> "ROLE_%03d_WITH_LONG_SUFFIX".formatted(index))
                .toList();

        assertEquals(roles.getFirst(), JdbcAuditSearchProjectionWriter.roleSummary(roles));
        assertEquals(true, JdbcAuditSearchProjectionWriter.roleSummary(roles).length() <= 128);
        assertNull(JdbcAuditSearchProjectionWriter.roleSummary(List.of()));
    }
}
