package cn.edu.suda.scholarsense.subjectregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityResult;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityState;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.subjectregistry.api.CurrentSubjectResolution;
import cn.edu.suda.scholarsense.subjectregistry.api.CurrentSubjectResolutionAdapter;
import cn.edu.suda.scholarsense.subjectregistry.api.CurrentSubjectResolutionPort;
import cn.edu.suda.scholarsense.subjectregistry.api.CurrentSubjectResolutionQuery;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionReason;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionType;
import cn.edu.suda.scholarsense.subjectregistry.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.LinkType;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import cn.edu.suda.scholarsense.subjectregistry.domain.ResolutionOutcome;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectLink;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingException;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingTimeline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SubjectRegistryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private static final ActorContext ACTOR = new ActorContext(
            "tenant-suda", "actor-r6", "audit-r6", "192.0.2.10");
    private static final TimeSourceProfile TIME_PROFILE = new TimeSourceProfile(
            "campus-ntp-subject", "AUDIT-CLOCK-BINDING-1.0.0", 3,
            NOW.minusSeconds(10), NOW.plusSeconds(60),
            "evidence://signed/clock/subject-registry.json");
    private static final TrustedTimeSource TIME = () -> new TrustedTime(NOW, TIME_PROFILE);
    private static final StudentRef STUDENT_A = StudentRef.of(
            uuid("019fcfea-5000-7000-8000-000000000001"));
    private static final StudentRef STUDENT_B = StudentRef.of(
            uuid("019fcfea-5000-7000-8000-000000000002"));
    private static final StudentRef STUDENT_C = StudentRef.of(
            uuid("019fcfea-5000-7000-8000-000000000003"));
    private static final UUID EXCEPTION_ID = uuid("019fcfea-5000-7000-8000-000000000010");

    @Test
    void ingestNormalizesAndProtectsBeforeAmbiguousInputIsIsolated() {
        MemoryStore store = new MemoryStore();
        store.authorityCandidates = List.of(STUDENT_A, STUDENT_B);
        RecordingProtection protection = new RecordingProtection();
        SubjectRegistryService service = service(store, protection, allow(true), currentRecheck());

        IngestSubjectIdentifierResult result = service.ingest(new IngestSubjectIdentifierCommand(
                "SRC-P0-CARD-001", "CARD-1.0.0", IdentifierType.CARD_NUMBER,
                " ００ab１２ ", " ００st００１ ",
                EffectiveInterval.of(NOW.minusSeconds(60), null), 1, "wm-001", ACTOR, TRACE));

        assertEquals(List.of("00AB12", "00ST001"), protection.protectedPlaintexts);
        assertEquals(ResolutionOutcome.AMBIGUOUS, result.outcome());
        assertTrue(result.studentRef().isEmpty());
        assertTrue(result.exceptionId().isPresent());
        assertEquals(1, store.ingestCommits.size());
        assertTrue(store.ingestCommits.getFirst().mapping().isEmpty());
        assertEquals(MappingExceptionCode.AMBIGUOUS,
                store.ingestCommits.getFirst().exceptionRecord().orElseThrow()
                        .exception().exceptionCode());
        assertFalse(store.ingestCommits.getFirst().toString().contains("00AB12"));
    }

    @Test
    void listAndDetailExposeOnlySevenApprovedFieldsAndClearOfficialRefOnlyWhenAuthorized() {
        MemoryStore store = storeWithOpenException();
        SubjectRegistryService clearService = service(
                store, new RecordingProtection(), allow(true), currentRecheck());

        List<SubjectMappingExceptionView> clear = clearService.listExceptions(
                0, 20, ACTOR, TRACE);
        assertEquals(1, clear.size());
        assertEquals(Optional.of("ST0001"), clear.getFirst().subjectOfficialRef());
        assertEquals(Set.of(
                "exceptionId", "status", "subjectOfficialRef", "exceptionCode",
                "sourceSystem", "sourceOwner", "detectedAt"),
                SubjectMappingExceptionView.FIELD_ALLOWLIST);

        SubjectRegistryService hiddenService = service(
                store, new RecordingProtection(), allow(false), currentRecheck());
        assertTrue(hiddenService.detailException(EXCEPTION_ID, ACTOR, TRACE)
                .subjectOfficialRef().isEmpty());
    }

    @Test
    void authorizationUsesTheControlledSourceTokenInsteadOfAnExceptionIdentifierToken() {
        MemoryStore store = storeWithOpenException();
        AtomicReference<cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest>
                captured = new AtomicReference<>();
        SubjectRegistryService service = service(
                store,
                new RecordingProtection(),
                request -> {
                    captured.set(request);
                    return decision(
                            CompositeAuthorizationOutcome.ALLOW,
                            Set.of("subjectOfficialRef"),
                            request.expectedObjectVersion());
                },
                currentRecheck());

        service.detailException(EXCEPTION_ID, ACTOR, TRACE);

        assertEquals(
                sha256("SRC-P0-CARD-001"),
                captured.get().objectTokenDigest());
        assertEquals("SUBJECT_MAPPING_EXCEPTION", captured.get().objectClass());
    }

    @Test
    void deniedExistingAndMissingObjectsUseTheSameNonDisclosingFailure() {
        MemoryStore store = storeWithOpenException();
        SubjectRegistryService denied = service(
                store, new RecordingProtection(), deny(), currentRecheck());
        UUID missing = uuid("019fcfea-5000-7000-8000-000000000099");

        SubjectRegistryApplicationException present = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> denied.detailException(EXCEPTION_ID, ACTOR, TRACE));
        SubjectRegistryApplicationException absent = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> denied.detailException(missing, ACTOR, TRACE));

        assertEquals("SUBJECT_REGISTRY_FORBIDDEN", present.code());
        assertEquals(present.code(), absent.code());
        assertEquals(present.httpStatus(), absent.httpStatus());
    }

    @Test
    void repairIsServerDigestedIdempotentCasAndCommitsCorrectionOutboxAuditTogether() {
        MemoryStore store = storeWithOpenException();
        SubjectRegistryService service = service(
                store, new RecordingProtection(), allow(true), currentRecheck());
        RepairSubjectMappingCommand command = repairCommand("idem-repair-001", 1, STUDENT_B);

        RepairSubjectMappingResult first = service.repair(command);
        RepairSubjectMappingResult replay = service.repair(command);

        assertEquals(first.exceptionId(), replay.exceptionId());
        assertEquals(first.correctionEventId(), replay.correctionEventId());
        assertEquals(first.recomputeRequestId(), replay.recomputeRequestId());
        assertEquals(1, store.repairWrites);
        assertEquals(1, store.corrections.size());
        assertEquals(1, store.recomputeRequests.size());
        assertEquals(1, store.audits.size());
        assertEquals(1, store.transactionCommits);
        assertEquals("subject-mapping.repair", store.audits.getFirst().action());
        assertFalse(store.audits.getFirst().toString().contains("ST0001"));

        SubjectRegistryApplicationException mismatch = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> service.repair(repairCommand("idem-repair-001", 1, STUDENT_C)));
        assertEquals("SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH", mismatch.code());
        assertEquals(1, store.repairWrites);
    }

    @Test
    void staleVersionAndStaleAuthorizationRecheckFailBeforeAnyMutation() {
        MemoryStore store = storeWithOpenException();
        SubjectRegistryService service = service(
                store, new RecordingProtection(), allow(true), staleRecheck());

        SubjectRegistryApplicationException staleAuth = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> service.repair(repairCommand("idem-stale-auth", 1, STUDENT_B)));
        assertEquals("SUBJECT_REGISTRY_FORBIDDEN", staleAuth.code());
        assertEquals(0, store.repairWrites);
        assertTrue(store.corrections.isEmpty());

        SubjectRegistryService current = service(
                store, new RecordingProtection(), allow(true), currentRecheck());
        SubjectRegistryApplicationException staleVersion = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> current.repair(repairCommand("idem-stale-version", 2, STUDENT_B)));
        assertEquals("SUBJECT_REGISTRY_VERSION_CONFLICT", staleVersion.code());
        assertEquals(1L, staleVersion.currentVersion());
        assertEquals(0, store.repairWrites);
    }

    @Test
    void unavailableAuditOrProtectionKeyFailsClosedWithoutMutation() {
        MemoryStore store = storeWithOpenException();
        SubjectRegistryService auditBlocked = service(
                store, new RecordingProtection(), allow(true), currentRecheck(),
                trace -> availability(AuditAvailabilityState.UNAVAILABLE));

        SubjectRegistryApplicationException auditError = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> auditBlocked.repair(repairCommand("idem-audit-down", 1, STUDENT_B)));
        assertEquals("SUBJECT_REGISTRY_AUDIT_UNAVAILABLE", auditError.code());
        assertEquals(0, store.repairWrites);

        RecordingProtection protectionDown = new RecordingProtection();
        protectionDown.available = false;
        SubjectRegistryService protectionBlocked = service(
                new MemoryStore(), protectionDown, allow(true), currentRecheck());
        SubjectRegistryApplicationException protectionError = assertThrows(
                SubjectRegistryApplicationException.class,
                () -> protectionBlocked.ingest(new IngestSubjectIdentifierCommand(
                        "SRC-P0-CARD-001", "CARD-1.0.0", IdentifierType.CARD_NUMBER,
                        "CARD001", "ST0001", EffectiveInterval.of(NOW, null),
                        1, "wm-key-down", ACTOR, TRACE)));
        assertEquals("SUBJECT_REGISTRY_PROTECTION_KEY_UNAVAILABLE", protectionError.code());
    }

    @Test
    void publicResolutionPortReturnsOnlyCanonicalSubjectRefAndNeverCandidateLists() {
        MemoryStore store = new MemoryStore();
        IdentifierKey key = key("SRC-P0-CARD-001", IdentifierType.CARD_NUMBER, "a");
        store.timelines.put(key, SubjectMappingTimeline.restoreForIsolation(List.of(
                TestMappings.mapping(key, STUDENT_A, 1),
                TestMappings.mapping(key, STUDENT_B, 1))));
        CurrentSubjectResolutionPort port = new CurrentSubjectResolutionAdapter(
                new SubjectResolutionQueryService(store));

        CurrentSubjectResolution result = port.resolve(new CurrentSubjectResolutionQuery(
                key.sourceId(), key.identifierType().wireValue(), key.token().environment(),
                key.token().keyRef(), key.token().keyVersion(), key.token().value(), NOW));

        assertEquals("ambiguous", result.outcome());
        assertTrue(result.subjectRef().isEmpty());
    }

    private static SubjectRegistryService service(
            MemoryStore store, RecordingProtection protection,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck) {
        return service(store, protection, authorization, recheck,
                trace -> availability(AuditAvailabilityState.HEALTHY));
    }

    private static SubjectRegistryService service(
            MemoryStore store, RecordingProtection protection,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort availability) {
        SubjectMappingExceptionRecord probe = recordFor(
                uuid("019fcfea-5000-7000-8000-000000000090"));
        return new SubjectRegistryService(
                store, protection, (sourceId, contractVersion, identifierType) ->
                        SourceIdentifierRule.approved(
                                sourceId, identifierType, "一卡通数据 owner",
                                NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1,
                                "SRC-P0-STUDENT-001".equals(sourceId)),
                store, authorization, recheck, store, store, store,
                availability, TIME, store, () -> probe);
    }

    private static MemoryStore storeWithOpenException() {
        MemoryStore store = new MemoryStore();
        store.exceptions.put(EXCEPTION_ID, recordFor(EXCEPTION_ID));
        return store;
    }

    private static SubjectMappingExceptionRecord recordFor(UUID id) {
        IdentifierKey key = key("SRC-P0-CARD-001", IdentifierType.CARD_NUMBER, "b");
        SubjectMappingException exception = SubjectMappingException.open(
                id, key, MappingExceptionCode.AMBIGUOUS, "一卡通数据 owner", NOW.minusSeconds(60));
        return new SubjectMappingExceptionRecord(
                exception,
                new ProtectedIdentifierMaterial(
                        key.token(), "aesgcm:official-ref", "STUDENT_OFFICIAL_REF"));
    }

    private static RepairSubjectMappingCommand repairCommand(
            String idempotencyKey, long expectedVersion, StudentRef target) {
        return new RepairSubjectMappingCommand(
                EXCEPTION_ID,
                expectedVersion,
                idempotencyKey,
                CorrectionReason.AUTHORITY_CORRECTION,
                "wm-repair-001",
                CorrectionType.CORRECT,
                SubjectLink.of(LinkType.ALIAS, STUDENT_A, List.of(target)),
                ACTOR,
                TRACE);
    }

    private static CompositeAuthorizationPort allow(boolean clearOfficialRef) {
        return request -> decision(
                CompositeAuthorizationOutcome.ALLOW,
                clearOfficialRef ? Set.of("subjectOfficialRef") : Set.of(),
                request.expectedObjectVersion());
    }

    private static CompositeAuthorizationPort deny() {
        return request -> decision(
                CompositeAuthorizationOutcome.DENY, Set.of(), request.expectedObjectVersion());
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationOutcome outcome, Set<String> clearFields, long objectVersion) {
        return new CompositeAuthorizationDecision(
                outcome,
                outcome == CompositeAuthorizationOutcome.ALLOW ? "ALLOW" : "DENY",
                Set.of("R6"), Set.of("owned-source"),
                Map.of("subjectOfficialRef", clearFields.isEmpty()
                        ? FieldVisibility.HIDDEN : FieldVisibility.CLEAR),
                clearFields, "RFP-1.0.0", objectVersion, NOW,
                new CompositeAuthorizationDecisionToken(
                        1, 1, 1, 1, 1, objectVersion, "RFP-1.0.0"));
    }

    private static CompositeAuthorizationRecheckPort currentRecheck() {
        return request -> new CompositeAuthorizationRecheckDecision(
                CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT");
    }

    private static CompositeAuthorizationRecheckPort staleRecheck() {
        return request -> new CompositeAuthorizationRecheckDecision(
                CompositeAuthorizationRecheckOutcome.STALE, "STALE");
    }

    private static AuditAvailabilityResult availability(AuditAvailabilityState state) {
        return new AuditAvailabilityResult(
                state, "AUDIT-INGESTION-POLICY-1.0.0",
                state == AuditAvailabilityState.HEALTHY ? Set.of() : Set.of("AUDIT_UNAVAILABLE"),
                NOW.minusSeconds(1), NOW.plusSeconds(30), TRACE);
    }

    private static IdentifierKey key(String sourceId, IdentifierType type, String hex) {
        return new IdentifierKey(
                sourceId, type,
                ProtectedIdentifierToken.of(
                        "prod", "kms://subject-registry/search", "v1",
                        "hmac-sha256:" + hex.repeat(64)));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class RecordingProtection implements IdentifierProtectionPort {
        private final List<String> protectedPlaintexts = new ArrayList<>();
        private boolean available = true;

        @Override
        public ProtectedIdentifierMaterial protect(
                String normalizedIdentifier, IdentifierProtectionContext context) {
            if (!available) throw new IdentifierProtectionUnavailableException();
            protectedPlaintexts.add(normalizedIdentifier);
            String digest = normalizedIdentifier.equals("00AB12") ? "c" : "d";
            return new ProtectedIdentifierMaterial(
                    ProtectedIdentifierToken.of(
                            "prod", "kms://subject-registry/search", "v1",
                            "hmac-sha256:" + digest.repeat(64)),
                    "aesgcm:" + digest.repeat(16), context.purpose());
        }

        @Override
        public String reveal(ProtectedIdentifierMaterial material) {
            if (!available) throw new IdentifierProtectionUnavailableException();
            return "ST0001";
        }
    }

    private static final class MemoryStore implements
            SubjectRegistryRepository,
            SubjectRegistryIdempotencyPort,
            SubjectRegistryTransactionPort,
            SubjectRegistryAuditPort,
            SubjectRegistryOutboxPort,
            SubjectRegistryIdPort {

        private List<StudentRef> authorityCandidates = List.of();
        private final Map<UUID, SubjectMappingExceptionRecord> exceptions = new HashMap<>();
        private final Map<IdentifierKey, SubjectMappingTimeline> timelines = new HashMap<>();
        private final List<IngestCommit> ingestCommits = new ArrayList<>();
        private final Map<RepairIdempotencyScope, RepairIdempotencyResult> idempotency = new HashMap<>();
        private final List<SubjectRegistryAuditEvent> audits = new ArrayList<>();
        private final List<cn.edu.suda.scholarsense.subjectregistry.domain.MappingCorrectionEvent>
                corrections = new ArrayList<>();
        private final List<MappingRecomputeRequestIntent> recomputeRequests = new ArrayList<>();
        private int repairWrites;
        private int transactionCommits;
        private long nextId = 100;

        @Override
        public List<StudentRef> findAuthorityCandidates(
                ProtectedIdentifierToken authorityToken, Instant at) {
            return authorityCandidates;
        }

        @Override
        public boolean identifierPreviouslyIssued(IdentifierKey key) {
            return false;
        }

        @Override
        public boolean isStudentRefReserved(StudentRef studentRef) {
            return false;
        }

        @Override
        public SubjectMappingTimeline timeline(IdentifierKey key) {
            return timelines.getOrDefault(key, SubjectMappingTimeline.empty());
        }

        @Override
        public Optional<SubjectMappingExceptionRecord> findException(UUID exceptionId) {
            return Optional.ofNullable(exceptions.get(exceptionId));
        }

        @Override
        public List<SubjectMappingExceptionRecord> listExceptions(int offset, int limit) {
            return exceptions.values().stream().skip(offset).limit(limit).toList();
        }

        @Override
        public void saveIngest(IngestCommit commit) {
            ingestCommits.add(commit);
            commit.exceptionRecord().ifPresent(record ->
                    exceptions.put(record.exception().exceptionId(), record));
            commit.mapping().ifPresent(mapping -> timelines.put(
                    mapping.identifierKey(), timeline(mapping.identifierKey()).append(mapping)));
        }

        @Override
        public RepairSubjectMappingResult saveRepair(RepairCommit commit, long expectedVersion) {
            SubjectMappingExceptionRecord current = exceptions.get(
                    commit.exceptionRecord().exception().exceptionId());
            if (current.exception().aggregateVersion() != expectedVersion) {
                throw new SubjectRegistryVersionConflictException(
                        current.exception().aggregateVersion());
            }
            repairWrites++;
            exceptions.put(commit.exceptionRecord().exception().exceptionId(), commit.exceptionRecord());
            return new RepairSubjectMappingResult(
                    commit.exceptionRecord().exception().exceptionId(),
                    commit.exceptionRecord().exception().status(),
                    commit.exceptionRecord().exception().aggregateVersion(),
                    commit.correctionEvent().eventId(), commit.recomputeRequest().requestId());
        }

        @Override
        public Optional<RepairIdempotencyResult> find(
                RepairIdempotencyScope scope, Instant at) {
            return Optional.ofNullable(idempotency.get(scope));
        }

        @Override
        public RepairIdempotencyClaim claim(
                RepairIdempotencyScope scope, String requestDigest, UUID exceptionId, Instant at) {
            RepairIdempotencyResult current = idempotency.get(scope);
            if (current == null) return RepairIdempotencyClaim.fresh();
            return current.requestDigest().equals(requestDigest)
                    ? RepairIdempotencyClaim.replay(current)
                    : RepairIdempotencyClaim.mismatch();
        }

        @Override
        public void complete(RepairIdempotencyResult result) {
            idempotency.put(result.scope(), result);
        }

        @Override
        public Object execute(java.util.function.Supplier<?> work) {
            Object result = work.get();
            transactionCommits++;
            return result;
        }

        @Override
        public void append(SubjectRegistryAuditEvent event) {
            audits.add(event);
        }

        @Override
        public void appendCorrection(
                cn.edu.suda.scholarsense.subjectregistry.domain.MappingCorrectionEvent event) {
            corrections.add(event);
        }

        @Override
        public void appendRecomputeRequest(MappingRecomputeRequestIntent request) {
            recomputeRequests.add(request);
        }

        @Override
        public UUID nextUuid() {
            return UUID.fromString(String.format(
                    "019fcfea-5000-7000-8000-%012d", nextId++));
        }

        @Override
        public StudentRef nextStudentRef() {
            return StudentRef.of(nextUuid());
        }
    }

    private static final class TestMappings {
        private static long next = 300;

        static cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping mapping(
                IdentifierKey key, StudentRef ref, long version) {
            return cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping.active(
                    generated(), generated(), key, ref, EffectiveInterval.of(NOW, null), version);
        }

        private static UUID generated() {
            return UUID.fromString(String.format(
                    "019fcfea-5000-7000-8000-%012d", next++));
        }
    }
}
