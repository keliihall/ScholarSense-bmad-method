package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HighRiskApprovalLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00.123456Z");

    @Test
    void allDistinctBusinessOwnersMustApproveAndMakerCannotCheck() {
        HighRiskApproval approval = pending();
        assertThrows(IllegalStateException.class,
                () -> approval.approve(digest('a'), NOW.plusSeconds(1)));

        approval.approve(digest('b'), NOW.plusSeconds(1));
        assertEquals(HighRiskApprovalStatus.PENDING, approval.status());
        approval.approve(digest('c'), NOW.plusSeconds(2));
        assertEquals(HighRiskApprovalStatus.APPROVED, approval.status());
        assertTrue(approval.mayIssueExecutionToken(NOW.plusSeconds(3)));
        assertEquals(2, approval.approvedCheckerDigests().size());
    }

    @Test
    void fourHourBoundaryIsHalfOpenAndRejectCancelAreTerminal() {
        HighRiskApproval atBoundary = pending();
        assertThrows(IllegalStateException.class, () ->
                atBoundary.approve(digest('b'), NOW.plusSeconds(4 * 60 * 60L)));
        atBoundary.expire(NOW.plusSeconds(4 * 60 * 60L));
        assertEquals(HighRiskApprovalStatus.EXPIRED, atBoundary.status());
        assertFalse(atBoundary.mayIssueExecutionToken(NOW.plusSeconds(1)));

        HighRiskApproval rejected = pending();
        rejected.reject(digest('b'), NOW.plusSeconds(1));
        assertEquals(HighRiskApprovalStatus.REJECTED, rejected.status());

        HighRiskApproval cancelled = pending();
        cancelled.cancel(digest('a'), NOW.plusSeconds(1));
        assertEquals(HighRiskApprovalStatus.CANCELLED, cancelled.status());
    }

    private static HighRiskApproval pending() {
        return HighRiskApproval.pending(
                uuid("019ff5a0-3000-7000-8000-000000000101"), binding(), NOW);
    }

    static HighRiskApprovalBinding binding() {
        return new HighRiskApprovalBinding(
                uuid("019ff5a0-3000-7000-8000-000000000102"), digest('1'),
                "quality-fuse.recover", digest('a'), digest('2'), digest('3'),
                "RECOVERY_TASK", digest('4'), 7, digest('5'), digest('6'),
                HighRiskApprovalBinding.DataSensitivity.HIGHLY_SENSITIVE_DEIDENTIFIED,
                "fused", "recovering", "QUALITY_RECOVERY", "HRAM-1.0.0", digest('7'),
                "HRAP-1.0.0", digest('8'), "RFP-1.0.0", digest('9'), digest('d'),
                digest('e'), List.of(digest('b'), digest('c')), 11,
                "00112233445566778899aabbccddeeff");
    }

    static String digest(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    static UUID uuid(String value) { return UUID.fromString(value); }
}
