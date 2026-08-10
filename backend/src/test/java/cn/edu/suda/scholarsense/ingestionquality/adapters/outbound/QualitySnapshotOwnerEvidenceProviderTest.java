package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class QualitySnapshotOwnerEvidenceProviderTest {
    private static final UUID ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000701");
    private static final UUID ORGANIZATION =
            UUID.fromString("019c1234-0000-7000-8000-000000000702");
    private static final UUID SNAPSHOT =
            UUID.fromString("019fe570-0000-7000-8000-000000000601");

    @TempDir
    Path temporaryDirectory;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void derivesCurrentPersistedSourceThenUsesTheUnmodifiedStaticSourceOwnerMount()
            throws Exception {
        byte[] bytes = document().getBytes(StandardCharsets.UTF_8);
        Path mount = temporaryDirectory.resolve("owner-bindings.json");
        Files.write(mount, bytes);
        CatalogOwnerEvidenceProvider sources = CatalogOwnerEvidenceProvider.load(
                mount, "sha256:" + sha256(bytes), new ObjectMapper());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("source_id")).thenReturn("SRC-P0-STUDENT-001");
        when(row.getLong("object_version")).thenReturn(3L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(row, 0)));
        var provider = new QualitySnapshotOwnerEvidenceProvider(jdbc, sources);

        var evidence = provider.resolve(new AuthorizationObjectEvidenceQuery(
                "session-pseudonym", ACCOUNT, Set.of(ORGANIZATION), "QUALITY_SNAPSHOT",
                "data-quality.read", QualitySnapshotQueryService.digest(SNAPSHOT), 3,
                Instant.parse("2026-08-10T00:00:00Z")));

        assertEquals(Set.of("QUALITY_SNAPSHOT"), provider.supportedObjectClasses());
        assertFalse(sources.supportedObjectClasses().contains("QUALITY_SNAPSHOT"));
        assertEquals(AuthorizationEvidenceAvailability.AVAILABLE, evidence.availability());
        assertEquals("data-quality.read", evidence.purpose());
        assertEquals(3, evidence.objectVersion());
        assertTrue(evidence.scopeEvidence().stream().anyMatch(scope ->
                scope.anchor() == AuthorizationScopeAnchor.OWNED_SOURCE
                        && ACCOUNT.equals(scope.accountId())));
    }

    private static String document() {
        List<String> bindings = new ArrayList<>();
        FrozenDataCatalogPolicy.expectedSourceIds().stream().sorted().forEach(sourceId ->
                bindings.add(("{\"objectClass\":\"SOURCE\",\"objectId\":\"%s\","
                        + "\"ownerAccountIds\":[\"%s\"],\"ownerOrganizationIds\":[],"
                        + "\"relationVersion\":41}").formatted(sourceId, ACCOUNT)));
        FrozenDataCatalogPolicy.expectedDependencies().values().stream().sorted()
                .forEach(dependencyId -> bindings.add(
                        ("{\"objectClass\":\"DEPENDENCY\",\"objectId\":\"%s\","
                                + "\"ownerAccountIds\":[],"
                                + "\"ownerOrganizationIds\":[\"%s\"],"
                                + "\"relationVersion\":42}")
                                .formatted(dependencyId, ORGANIZATION)));
        return "{\"version\":\"INGESTION-QUALITY-OWNER-BINDINGS-1.0.0\","
                + "\"bindings\":[" + String.join(",", bindings) + "]}";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
