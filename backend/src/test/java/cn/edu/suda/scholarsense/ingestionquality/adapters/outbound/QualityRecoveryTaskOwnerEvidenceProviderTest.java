package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QualityRecoveryTaskOwnerEvidenceProviderTest {
    private static final UUID ACCOUNT = UUID.fromString(
            "019ff5a0-4000-7000-8000-000000000101");

    @Test
    @SuppressWarnings("unchecked")
    void bindsTaskDigestToPersistedSourceCurrentVersionAndOpenState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("sha256:" + "a".repeat(64))))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet row = mock(java.sql.ResultSet.class);
                    when(row.getString("source_id")).thenReturn("SRC-P0-CARD-001");
                    when(row.getLong("object_version")).thenReturn(7L);
                    when(row.getString("status")).thenReturn("open");
                    return List.of(mapper.mapRow(row, 0));
                });
        CatalogOwnerEvidenceProvider sources = mock(CatalogOwnerEvidenceProvider.class);
        when(sources.resolve(any())).thenReturn(availableSource());
        var provider = new QualityRecoveryTaskOwnerEvidenceProvider(
                jdbc, sources, ignored -> new HighRiskApprovalEvidence(
                        HighRiskApprovalEvidence.Availability.AVAILABLE, "approved", 2,
                        "sha256:" + "f".repeat(64), 3, null));

        AuthorizationObjectEvidence evidence = provider.resolve(query(7));

        assertEquals(AuthorizationEvidenceAvailability.AVAILABLE, evidence.availability());
        assertEquals(7, evidence.objectVersion());
        assertEquals(Set.of("quality-fuse.recover"), Set.of(evidence.purpose()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void versionDriftOrMissingTaskFailsClosedWithoutEchoingExpectedVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("sha256:" + "a".repeat(64))))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet row = mock(java.sql.ResultSet.class);
                    when(row.getString("source_id")).thenReturn("SRC-P0-CARD-001");
                    when(row.getLong("object_version")).thenReturn(8L);
                    when(row.getString("status")).thenReturn("open");
                    return List.of(mapper.mapRow(row, 0));
                });
        var provider = new QualityRecoveryTaskOwnerEvidenceProvider(
                jdbc, mock(CatalogOwnerEvidenceProvider.class));
        assertEquals(AuthorizationEvidenceAvailability.UNAVAILABLE,
                provider.resolve(query(7)).availability());
    }

    private static AuthorizationObjectEvidenceQuery query(long version) {
        return new AuthorizationObjectEvidenceQuery(
                "actor", ACCOUNT, Set.of(), "RECOVERY_TASK", "quality-fuse.recover",
                "a".repeat(64), version, Instant.parse("2026-08-13T00:00:00Z"));
    }

    private static AuthorizationObjectEvidence availableSource() {
        return new AuthorizationObjectEvidence(
                AuthorizationEvidenceAvailability.AVAILABLE,
                Set.of(new AuthorizationScopeEvidence(
                        AuthorizationScopeAnchor.OWNED_SOURCE, ACCOUNT, null)),
                "quality-fuse.recover", Set.of(), null, null, Set.of(), false,
                3, 0, 0, 7, Optional.empty());
    }
}
