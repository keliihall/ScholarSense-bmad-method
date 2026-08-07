package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CatalogOwnerEvidenceProviderTest {
    private static final UUID ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000701");
    private static final UUID ORGANIZATION =
            UUID.fromString("019c1234-0000-7000-8000-000000000702");

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsDigestBoundSourceAndDependencyOwnersFromTheMountedProfile() throws Exception {
        byte[] document = document().getBytes(StandardCharsets.UTF_8);
        Path path = temporaryDirectory.resolve("owner-bindings.json");
        Files.write(path, document);
        CatalogOwnerEvidenceProvider provider = CatalogOwnerEvidenceProvider.load(
                path, "sha256:" + sha256(document), new ObjectMapper());

        var source = provider.resolve(query(
                "SOURCE", "SRC-P0-CALENDAR-001", "data-quality.repair", 9));
        var dependency = provider.resolve(query(
                "DEPENDENCY", "DEP-P0-CALENDAR-001", "data-quality.reconcile", 10));

        assertEquals(AuthorizationEvidenceAvailability.AVAILABLE, source.availability());
        assertEquals(9, source.objectVersion());
        assertEquals(41, source.relationVersion());
        assertTrue(source.scopeEvidence().stream().anyMatch(scope ->
                scope.anchor() == AuthorizationScopeAnchor.OWNED_SOURCE
                        && ACCOUNT.equals(scope.accountId())));
        assertEquals(AuthorizationEvidenceAvailability.AVAILABLE, dependency.availability());
        assertEquals(10, dependency.objectVersion());
        assertTrue(dependency.scopeEvidence().stream().anyMatch(scope ->
                scope.anchor() == AuthorizationScopeAnchor.OWNED_SOURCE
                        && ORGANIZATION.equals(scope.organizationId())));
    }

    @Test
    void projectsControlledSourceOwnershipForMappingExceptionsAndRecomputeJobs() throws Exception {
        byte[] document = document().getBytes(StandardCharsets.UTF_8);
        Path path = temporaryDirectory.resolve("owner-bindings.json");
        Files.write(path, document);
        CatalogOwnerEvidenceProvider provider = CatalogOwnerEvidenceProvider.load(
                path, "sha256:" + sha256(document), new ObjectMapper());

        var exception = provider.resolve(query(
                "SUBJECT_MAPPING_EXCEPTION", "SRC-P0-CALENDAR-001",
                "data-quality.read", 3));
        var ownerJob = provider.resolve(query(
                "JOB", "SRC-P0-CALENDAR-001", "data-quality.read", 4));
        var technicalJob = provider.resolve(query(
                "JOB", "SRC-P0-CALENDAR-001", "platform.read", 4));

        assertEquals(AuthorizationEvidenceAvailability.AVAILABLE, exception.availability());
        assertEquals("SUBJECT_MAPPING_EXCEPTION_REPAIR", exception.purpose());
        assertEquals(Set.of(
                "exceptionId", "status", "subjectOfficialRef", "exceptionCode",
                "sourceSystem", "sourceOwner", "detectedAt"), exception.fieldAllowlist());
        assertTrue(exception.scopeEvidence().stream().anyMatch(scope ->
                scope.anchor() == AuthorizationScopeAnchor.OWNED_SOURCE));
        assertTrue(ownerJob.scopeEvidence().stream().anyMatch(scope ->
                scope.anchor() == AuthorizationScopeAnchor.OWNED_SOURCE));
        assertTrue(technicalJob.scopeEvidence().stream().anyMatch(scope ->
                scope.anchor() == AuthorizationScopeAnchor.TECHNICAL_OBJECT));
    }

    @Test
    void unknownOwnedObjectFailsClosedAndProfileDigestMismatchStopsStartup() throws Exception {
        byte[] document = document().getBytes(StandardCharsets.UTF_8);
        Path path = temporaryDirectory.resolve("owner-bindings.json");
        Files.write(path, document);
        CatalogOwnerEvidenceProvider provider = CatalogOwnerEvidenceProvider.load(
                path, "sha256:" + sha256(document), new ObjectMapper());

        assertEquals(
                AuthorizationEvidenceAvailability.UNAVAILABLE,
                provider.resolve(query(
                        "SOURCE", "SRC-P0-UNKNOWN-999", "data-quality.read", 9))
                        .availability());
        assertThrows(
                IllegalArgumentException.class,
                () -> CatalogOwnerEvidenceProvider.load(
                        path, "sha256:" + "0".repeat(64), new ObjectMapper()));
    }

    @Test
    void duplicateBindingsAndUnsupportedObjectClassesStopStartup() throws Exception {
        String duplicate = document().replace(
                "\"bindings\":[",
                "\"bindings\":[{\"objectClass\":\"SOURCE\","
                        + "\"objectId\":\"SRC-P0-CALENDAR-001\","
                        + "\"ownerAccountIds\":[\"" + ACCOUNT + "\"],"
                        + "\"ownerOrganizationIds\":[],\"relationVersion\":41},");
        byte[] duplicateBytes = duplicate.getBytes(StandardCharsets.UTF_8);
        Path duplicatePath = temporaryDirectory.resolve("duplicate.json");
        Files.write(duplicatePath, duplicateBytes);
        assertThrows(IllegalArgumentException.class, () -> CatalogOwnerEvidenceProvider.load(
                duplicatePath, "sha256:" + sha256(duplicateBytes), new ObjectMapper()));

        String unsupported = document().replace("\"SOURCE\"", "\"CANDIDATE\"");
        byte[] unsupportedBytes = unsupported.getBytes(StandardCharsets.UTF_8);
        Path unsupportedPath = temporaryDirectory.resolve("unsupported.json");
        Files.write(unsupportedPath, unsupportedBytes);
        assertThrows(IllegalArgumentException.class, () -> CatalogOwnerEvidenceProvider.load(
                unsupportedPath, "sha256:" + sha256(unsupportedBytes), new ObjectMapper()));
    }

    @Test
    void rejectsARegularProfileReplacementBetweenValidationAndOpen() throws Exception {
        byte[] document = document().getBytes(StandardCharsets.UTF_8);
        Path path = temporaryDirectory.resolve("replaceable-owner-bindings.json");
        Path replacement = temporaryDirectory.resolve("replacement-owner-bindings.json");
        Files.write(path, document);
        Files.write(replacement, document);

        assertThrows(
                IllegalArgumentException.class,
                () -> CatalogOwnerEvidenceProvider.load(
                        path,
                        "sha256:" + sha256(document),
                        new ObjectMapper(),
                        () -> replace(replacement, path)));
    }

    private static void replace(Path replacement, Path target) {
        try {
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static AuthorizationObjectEvidenceQuery query(
            String objectClass, String objectId, String action, long version) {
        return new AuthorizationObjectEvidenceQuery(
                "actor-pseudonym",
                ACCOUNT,
                Set.of(ORGANIZATION),
                objectClass,
                action,
                sha256(objectId.getBytes(StandardCharsets.UTF_8)),
                version,
                Instant.parse("2026-08-05T00:00:00Z"));
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
