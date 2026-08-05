package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DataSourceCatalogServiceTest {

    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000011");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000012");
    private static final CatalogActorContext ACTOR = new CatalogActorContext(
            "session-r6", "actor-r6", "192.0.2.10");
    private static final Instant CLIENT_TIME = Instant.parse("2036-08-04T12:00:00Z");
    private static final Instant SERVER_TIME = Instant.parse("2026-08-05T01:02:03Z");
    private static final TimeSourceProfile TIME_PROFILE = new TimeSourceProfile(
            "campus-ntp-catalog", "AUDIT-CLOCK-BINDING-1.0.0", 37,
            SERVER_TIME.minusSeconds(10), SERVER_TIME.plusSeconds(60),
            "evidence://signed/clock/catalog-nonzero-offset.json");
    private static final TrustedTimeSource TIME =
            () -> new TrustedTime(SERVER_TIME, TIME_PROFILE);

    @Test
    void validateUsesTrustedServerClockAndPersistsInvalidAttemptWithoutChangingCurrentPointer() {
        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME));
        DataSourceCatalogService service = service(store, catalog -> List.of(
                new CatalogContractViolation("DCC_OWNER_MISSING", "sources[0].ownerName")), allowAll());

        CatalogView result = service.validate(new ValidateCatalogCommand(
                CATALOG_ID, 1, ACTOR, "trace-001"));

        assertEquals(CatalogStatus.INVALID, result.status());
        assertEquals(2, result.aggregateVersion());
        assertEquals(SERVER_TIME, result.updatedAt());
        assertEquals(0, result.currentPointerVersion());
        assertEquals(Optional.empty(), store.current());
        assertEquals(SERVER_TIME, store.audits.getFirst().occurredAt());
        assertEquals(TIME_PROFILE, store.audits.getFirst().timeSourceProfile());
        assertEquals("actor-r6", store.audits.getFirst().auditActorRef());
        assertEquals("192.0.2.10", store.audits.getFirst().sourceIp());
        assertEquals("trace-001", store.validationTrace);
    }

    @Test
    void publishUsesSeparateSourceAndDependencyActionsAndServerComputedEvidenceDigest() {
        MemoryStore store = new MemoryStore();
        DataSourceCatalog publishable = CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME)
                .validated(List.of(), CLIENT_TIME);
        store.catalogs.put(CATALOG_ID, publishable);
        List<ActionPair> actions = new ArrayList<>();
        DataSourceCatalogService service = service(store, catalog -> List.of(),
                (actor, sourceAction, dependencyAction, catalog, trace) -> {
                    actions.add(new ActionPair(actor, sourceAction, dependencyAction));
                    return CatalogAuthorizationDecision.ALLOW;
                });
        PublishCatalogCommand command = command("idem-key-001", "sha256:" + "c".repeat(64), 0);

        CatalogView first = service.publish(command);
        CatalogView replay = service.publish(command);

        assertEquals(first, replay);
        assertEquals(CatalogStatus.PUBLISHED, first.status());
        assertEquals(SERVER_TIME, first.publishedAt());
        assertEquals(1, first.currentPointerVersion());
        assertEquals(CatalogFixtures.evidence(publishable, SERVER_TIME).digest(), first.evidenceSetDigest());
        assertEquals(17, store.persistedEvidenceCount);
        assertEquals(CATALOG_ID, store.current().orElseThrow().catalogId());
        assertEquals(1, store.publishWrites);
        assertTrue(actions.contains(new ActionPair(
                "session-r6", "data-quality.repair", "data-quality.reconcile")));
        assertEquals(1, store.audits.size(), "idempotent replay must not append another audit fact");
        assertEquals(sha256("idem-key-001"), store.audits.getFirst().idempotencyKeyDigest());

        PublishCatalogCommand mismatch = command(
                "idem-key-001", "sha256:" + "e".repeat(64), 0);
        IngestionQualityApplicationException error = assertThrows(
                IngestionQualityApplicationException.class, () -> service.publish(mismatch));
        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", error.code());
    }

    @Test
    void staleCatalogOrCurrentPointerVersionReturnsStableConflictBeforeMutation() {
        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID,
                CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME).validated(List.of(), CLIENT_TIME));
        DataSourceCatalogService service = service(store, catalog -> List.of(), allowAll());

        IngestionQualityApplicationException staleCatalog = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.publish(new PublishCatalogCommand(
                        CATALOG_ID, 1, 0, RELEASE_ID, "idem-stale-catalog",
                        "sha256:" + "c".repeat(64), ACTOR, "trace-003")));
        assertEquals("INGESTION_QUALITY_VERSION_CONFLICT", staleCatalog.code());
        assertEquals(CatalogStatus.PUBLISHABLE, store.catalogs.get(CATALOG_ID).status());

        store.pointer = new CatalogCurrentPointer(CATALOG_ID, 2, 4);
        IngestionQualityApplicationException stalePointer = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.publish(command("idem-stale-pointer", "sha256:" + "d".repeat(64), 3)));
        assertEquals("INGESTION_QUALITY_VERSION_CONFLICT", stalePointer.code());
        assertEquals(4, stalePointer.currentVersion());
        assertEquals(CatalogStatus.PUBLISHABLE, store.catalogs.get(CATALOG_ID).status());
    }

    @Test
    void versionCommandsAcceptTheSafeMaximumButRejectOverflowAndCannotAdvanceIt() {
        assertThrows(IllegalArgumentException.class, () -> new ValidateCatalogCommand(
                CATALOG_ID, DataSourceCatalog.MAX_VERSION + 1, ACTOR, "trace-max-validate"));
        assertThrows(IllegalArgumentException.class, () -> new PublishCatalogCommand(
                CATALOG_ID, 2, DataSourceCatalog.MAX_VERSION + 1, RELEASE_ID,
                "idem-max-overflow", "sha256:" + "f".repeat(64), ACTOR,
                "trace-max-overflow"));

        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID,
                CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME).validated(List.of(), CLIENT_TIME));
        DataSourceCatalogService service = service(store, catalog -> List.of(), allowAll());
        IngestionQualityApplicationException conflict = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.publish(command(
                        "idem-max", "sha256:" + "f".repeat(64),
                        DataSourceCatalog.MAX_VERSION)));

        assertEquals("INGESTION_QUALITY_VERSION_CONFLICT", conflict.code());
        assertEquals(DataSourceCatalog.MAX_VERSION, conflict.currentVersion());
        assertEquals(0, store.publishWrites);
        assertTrue(store.idempotency.isEmpty());
    }

    @Test
    void listPaginatesTheAuthorizationVisibleSequenceWithLimitPlusOne() {
        MemoryStore store = new MemoryStore();
        List<UUID> ids = List.of(
                UUID.fromString("019fc6b8-9400-7000-8000-000000000021"),
                UUID.fromString("019fc6b8-9400-7000-8000-000000000022"),
                UUID.fromString("019fc6b8-9400-7000-8000-000000000023"),
                UUID.fromString("019fc6b8-9400-7000-8000-000000000024"),
                UUID.fromString("019fc6b8-9400-7000-8000-000000000025"));
        ids.forEach(id -> store.catalogs.put(id, CatalogFixtures.draft(id, CLIENT_TIME)));
        DataSourceCatalogService service = service(store, catalog -> List.of(),
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        catalog.catalogId().equals(ids.get(1)) || catalog.catalogId().equals(ids.get(3))
                                || catalog.catalogId().equals(ids.get(4))
                                ? CatalogAuthorizationDecision.ALLOW : CatalogAuthorizationDecision.DENY);

        List<CatalogView> page = service.list(1, 1, ACTOR, "trace-list");

        assertEquals(2, page.size(), "limit+1 must be returned so the controller can compute hasMore");
        assertEquals(ids.get(3), page.get(0).catalogId());
        assertEquals(ids.get(4), page.get(1).catalogId());
        assertTrue(store.largestRequestedLimit > 1);
    }

    @Test
    void listFailsClosedWhenAuthorizationDependencyIsUnavailable() {
        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME));
        DataSourceCatalogService service = service(store, catalog -> List.of(),
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        CatalogAuthorizationDecision.DEPENDENCY_UNAVAILABLE);

        IngestionQualityApplicationException error = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.list(0, 20, ACTOR, "trace-list"));

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", error.code());
    }

    @Test
    void existingAndConcealedMissingObjectsHaveTheSameAuthorizationOutcomes() {
        UUID missingId = UUID.fromString("019fc6b8-9400-7000-8000-000000000099");
        MemoryStore unavailableStore = new MemoryStore();
        unavailableStore.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME));
        DataSourceCatalogService unavailable = service(
                unavailableStore, catalog -> List.of(),
                new cn.edu.suda.scholarsense.ingestionquality.adapters.outbound
                        .CompositeCatalogAuthorizationAdapter(request -> {
                            throw new IllegalStateException(
                                    "mandatory authorization audit unavailable");
                        }));

        assertSameFailure("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                () -> unavailable.get(CATALOG_ID, ACTOR, "00112233445566778899aabbccddeeff"),
                () -> unavailable.get(missingId, ACTOR, "00112233445566778899aabbccddeeff"));
        assertSameFailure("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                () -> unavailable.getDetail(CATALOG_ID, ACTOR, "00112233445566778899aabbccddeeff"),
                () -> unavailable.getDetail(missingId, ACTOR, "00112233445566778899aabbccddeeff"));
        assertSameFailure("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                () -> unavailable.validate(new ValidateCatalogCommand(
                        CATALOG_ID, 1, ACTOR, "00112233445566778899aabbccddeeff")),
                () -> unavailable.validate(new ValidateCatalogCommand(
                        missingId, 1, ACTOR, "00112233445566778899aabbccddeeff")));
        assertSameFailure("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                () -> unavailable.publish(commandFor(
                        CATALOG_ID, "idem-oracle-present", "00112233445566778899aabbccddeeff")),
                () -> unavailable.publish(commandFor(
                        missingId, "idem-oracle-missing", "00112233445566778899aabbccddeeff")));

        MemoryStore deniedStore = new MemoryStore();
        deniedStore.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME));
        DataSourceCatalogService denied = service(
                deniedStore, catalog -> List.of(),
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        CatalogAuthorizationDecision.DENY);
        assertSameFailure("INGESTION_QUALITY_FORBIDDEN",
                () -> denied.get(CATALOG_ID, ACTOR, "trace-oracle-deny-present"),
                () -> denied.get(missingId, ACTOR, "trace-oracle-deny-missing"));

        DataSourceCatalogService allowed = service(deniedStore, catalog -> List.of(), allowAll());
        IngestionQualityApplicationException concealed = assertThrows(
                IngestionQualityApplicationException.class,
                () -> allowed.get(missingId, ACTOR, "trace-oracle-allow-missing"));
        assertEquals("INGESTION_QUALITY_FORBIDDEN", concealed.code());
    }

    @Test
    void validatedAuthorizationProbeIsCachedAtStartupForBothExistenceBranches() {
        UUID missingId = UUID.fromString("019fc6b8-9400-7000-8000-000000000098");
        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME));
        AtomicInteger probeReads = new AtomicInteger();
        CatalogAuthorizationProbePort probe = () -> {
            if (probeReads.incrementAndGet() > 1) {
                throw new IllegalStateException("mount disappeared after startup");
            }
            return CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME);
        };
        DataSourceCatalogService service = new DataSourceCatalogService(
                store, store, catalog -> List.of(),
                (actor, sourceAction, dependencyAction, catalog, trace) ->
                        CatalogAuthorizationDecision.DENY,
                work -> work.get(), event -> store.audits.add(event), trace -> {},
                catalog -> CatalogFixtures.evidence(catalog, SERVER_TIME), probe, TIME);

        assertSameFailure("INGESTION_QUALITY_FORBIDDEN",
                () -> service.get(CATALOG_ID, ACTOR, "trace-cached-probe-present"),
                () -> service.get(missingId, ACTOR, "trace-cached-probe-missing"));
        assertEquals(1, probeReads.get());
    }

    @Test
    void validateAndPublishMapTrustedTimeFailureToDependencyUnavailableBeforeMutation() {
        MemoryStore validateStore = new MemoryStore();
        validateStore.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME));
        TrustedTimeSource unavailable = () -> {
            throw new IllegalStateException("trusted time unavailable");
        };
        DataSourceCatalogService validateService = service(
                validateStore, catalog -> List.of(), allowAll(), unavailable);

        IngestionQualityApplicationException validateFailure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> validateService.validate(new ValidateCatalogCommand(
                        CATALOG_ID, 1, ACTOR, "trace-time-validate")));

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", validateFailure.code());
        assertEquals(CatalogStatus.DRAFT, validateStore.catalogs.get(CATALOG_ID).status());
        assertTrue(validateStore.audits.isEmpty());

        MemoryStore publishStore = new MemoryStore();
        publishStore.catalogs.put(CATALOG_ID,
                CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME).validated(List.of(), CLIENT_TIME));
        DataSourceCatalogService publishService = service(
                publishStore, catalog -> List.of(), allowAll(), unavailable);
        IngestionQualityApplicationException publishFailure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> publishService.publish(command(
                        "idem-time-unavailable", "sha256:" + "a".repeat(64), 0)));

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", publishFailure.code());
        assertEquals(CatalogStatus.PUBLISHABLE, publishStore.catalogs.get(CATALOG_ID).status());
        assertEquals(0, publishStore.publishWrites);
    }

    private DataSourceCatalogService service(
            MemoryStore store, CatalogContractPolicyPort policy,
            CatalogAuthorizationPort authorization) {
        return service(store, policy, authorization, TIME);
    }

    private DataSourceCatalogService service(
            MemoryStore store, CatalogContractPolicyPort policy,
            CatalogAuthorizationPort authorization, TrustedTimeSource time) {
        return new DataSourceCatalogService(
                store, store, policy, authorization, work -> work.get(),
                event -> store.audits.add(event), trace -> {},
                catalog -> CatalogFixtures.evidence(catalog, SERVER_TIME),
                () -> CatalogFixtures.draft(CATALOG_ID, CLIENT_TIME), time);
    }

    private static CatalogAuthorizationPort allowAll() {
        return (actor, sourceAction, dependencyAction, catalog, trace) ->
                CatalogAuthorizationDecision.ALLOW;
    }

    private static PublishCatalogCommand command(
            String idempotencyKey, String requestDigest, long expectedCurrentVersion) {
        return new PublishCatalogCommand(
                CATALOG_ID, 2, expectedCurrentVersion, RELEASE_ID, idempotencyKey,
                requestDigest, ACTOR, "trace-002");
    }

    private static PublishCatalogCommand commandFor(
            UUID catalogId, String idempotencyKey, String traceId) {
        return new PublishCatalogCommand(
                catalogId, 1, 0, RELEASE_ID, idempotencyKey,
                "sha256:" + "a".repeat(64), ACTOR, traceId);
    }

    private static void assertSameFailure(
            String expectedCode, Runnable existing, Runnable missing) {
        IngestionQualityApplicationException existingFailure = assertThrows(
                IngestionQualityApplicationException.class, existing::run);
        IngestionQualityApplicationException missingFailure = assertThrows(
                IngestionQualityApplicationException.class, missing::run);
        assertEquals(expectedCode, existingFailure.code());
        assertEquals(expectedCode, missingFailure.code());
    }

    private record ActionPair(
            String authorizationSessionRef, String sourceAction, String dependencyAction) {}

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class MemoryStore implements CatalogRepository, CatalogIdempotencyPort {
        private final Map<UUID, DataSourceCatalog> catalogs = new HashMap<>();
        private final Map<String, CatalogIdempotencyResult> idempotency = new HashMap<>();
        private final List<CatalogAuditEvent> audits = new ArrayList<>();
        private CatalogCurrentPointer pointer;
        private int publishWrites;
        private int persistedEvidenceCount;
        private int largestRequestedLimit;
        private String validationTrace;

        @Override
        public Optional<DataSourceCatalog> find(UUID id) {
            return Optional.ofNullable(catalogs.get(id));
        }

        @Override
        public List<DataSourceCatalog> list(int offset, int limit) {
            largestRequestedLimit = Math.max(largestRequestedLimit, limit);
            return catalogs.values().stream().sorted(Comparator.comparing(DataSourceCatalog::catalogId))
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public Optional<CatalogCurrentPointer> currentPointer() {
            return Optional.ofNullable(pointer);
        }

        @Override
        public void save(DataSourceCatalog catalog, long expectedVersion) {
            DataSourceCatalog previous = catalogs.get(catalog.catalogId());
            long actual = previous == null ? 0 : previous.aggregateVersion();
            if (actual != expectedVersion) throw new CatalogVersionConflictException(actual);
            catalogs.put(catalog.catalogId(), catalog);
        }

        @Override
        public void saveValidation(
                DataSourceCatalog catalog, long expectedVersion, String traceId) {
            save(catalog, expectedVersion);
            validationTrace = traceId;
        }

        @Override
        public void publish(
                DataSourceCatalog catalog, long expectedVersion, long expectedCurrentVersion,
                CatalogEvidenceSet evidence) {
            long actualCatalog = find(catalog.catalogId()).map(DataSourceCatalog::aggregateVersion).orElse(0L);
            long actualPointer = pointer == null ? 0 : pointer.pointerVersion();
            if (actualCatalog != expectedVersion) throw new CatalogVersionConflictException(actualCatalog);
            if (actualPointer != expectedCurrentVersion) throw new CatalogVersionConflictException(actualPointer);
            catalogs.put(catalog.catalogId(), catalog);
            pointer = new CatalogCurrentPointer(
                    catalog.catalogId(), catalog.aggregateVersion(), actualPointer + 1);
            persistedEvidenceCount = evidence.items().size();
            publishWrites++;
        }

        @Override
        public synchronized Optional<CatalogIdempotencyResult> find(String key, Instant now) {
            return Optional.ofNullable(idempotency.get(key));
        }

        @Override
        public synchronized CatalogIdempotencyClaim claim(
                String key, String requestDigest, UUID catalogId, Instant now) {
            CatalogIdempotencyResult existing = idempotency.get(key);
            if (existing == null) return CatalogIdempotencyClaim.acquired();
            if (!existing.requestDigest().equals(requestDigest)) return CatalogIdempotencyClaim.mismatch();
            return CatalogIdempotencyClaim.replay(existing);
        }

        @Override
        public synchronized void complete(CatalogIdempotencyResult result, Instant completedAt) {
            idempotency.put(result.idempotencyKey(), result);
        }
    }
}
