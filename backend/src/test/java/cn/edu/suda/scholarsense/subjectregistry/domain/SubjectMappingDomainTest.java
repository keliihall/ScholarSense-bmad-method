package cn.edu.suda.scholarsense.subjectregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectMappingDomainTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-01T00:00:00Z");
    private static final StudentRef STUDENT_A = studentRef("019fcfea-4000-7000-8000-000000000001");
    private static final StudentRef STUDENT_B = studentRef("019fcfea-4000-7000-8000-000000000002");
    private static final IdentifierKey CARD_KEY = new IdentifierKey(
            "SRC-P0-CARD-001",
            IdentifierType.CARD_NUMBER,
            ProtectedIdentifierToken.of(
                    "prod", "kms://subject-registry/search", "v1", "hmac-sha256:" + "a".repeat(64)));

    @Test
    void resolutionIsExactAndIsolatesZeroOrManyCandidates() {
        SubjectMappingTimeline empty = SubjectMappingTimeline.empty();
        assertEquals(ResolutionOutcome.NO_MATCH, empty.resolve(CARD_KEY, START).outcome());

        SubjectMapping first = mapping(
                "019fcfea-4000-7000-8000-000000000010",
                "019fcfea-4000-7000-8000-000000000020",
                CARD_KEY, STUDENT_A, START, END, 1);
        SubjectMappingTimeline unique = empty.append(first);
        SubjectResolution resolved = unique.resolve(CARD_KEY, START);
        assertEquals(ResolutionOutcome.UNIQUE, resolved.outcome());
        assertEquals(STUDENT_A, resolved.studentRef().orElseThrow());

        SubjectMapping secondCandidate = mapping(
                "019fcfea-4000-7000-8000-000000000011",
                "019fcfea-4000-7000-8000-000000000021",
                CARD_KEY, STUDENT_B, START, END, 1);
        SubjectResolution ambiguous = SubjectMappingTimeline.restoreForIsolation(
                List.of(first, secondCandidate)).resolve(CARD_KEY, START);
        assertEquals(ResolutionOutcome.AMBIGUOUS, ambiguous.outcome());
        assertTrue(ambiguous.studentRef().isEmpty());
    }

    @Test
    void halfOpenIntervalsAllowAdjacencyAndExcludeRightBoundary() {
        SubjectMapping first = mapping(
                "019fcfea-4000-7000-8000-000000000012",
                "019fcfea-4000-7000-8000-000000000022",
                CARD_KEY, STUDENT_A, START, END, 1);
        SubjectMapping adjacent = mapping(
                "019fcfea-4000-7000-8000-000000000013",
                "019fcfea-4000-7000-8000-000000000023",
                CARD_KEY, STUDENT_B, END, null, 1);

        SubjectMappingTimeline timeline = SubjectMappingTimeline.empty().append(first).append(adjacent);

        assertEquals(STUDENT_A, timeline.resolve(CARD_KEY, END.minusNanos(1)).studentRef().orElseThrow());
        assertEquals(STUDENT_B, timeline.resolve(CARD_KEY, END).studentRef().orElseThrow());
    }

    @Test
    void oneNanosecondOverlapIsRejectedButDifferentSourceOrTypeDoesNotCollide() {
        SubjectMapping first = mapping(
                "019fcfea-4000-7000-8000-000000000014",
                "019fcfea-4000-7000-8000-000000000024",
                CARD_KEY, STUDENT_A, START, END, 1);
        SubjectMapping overlap = mapping(
                "019fcfea-4000-7000-8000-000000000015",
                "019fcfea-4000-7000-8000-000000000025",
                CARD_KEY, STUDENT_B, END.minusNanos(1), null, 1);
        SubjectMappingTimeline timeline = SubjectMappingTimeline.empty().append(first);

        SubjectRegistryException error = assertThrows(
                SubjectRegistryException.class, () -> timeline.append(overlap));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_MAPPING_OVERLAP, error.code());

        IdentifierKey otherSource = new IdentifierKey(
                "SRC-P0-DORM-ACCESS-001", IdentifierType.CARD_NUMBER, CARD_KEY.token());
        assertEquals(2, timeline.append(mapping(
                "019fcfea-4000-7000-8000-000000000016",
                "019fcfea-4000-7000-8000-000000000026",
                otherSource, STUDENT_B, START, null, 1)).mappings().size());
    }

    @Test
    void mappingAggregateVersionMustIncreaseAndCannotOverflowJsonSafeInteger() {
        UUID aggregateId = uuid("019fcfea-4000-7000-8000-000000000030");
        SubjectMapping first = mapping(
                "019fcfea-4000-7000-8000-000000000017",
                aggregateId.toString(), CARD_KEY, STUDENT_A, START, END, 1);
        SubjectMappingTimeline timeline = SubjectMappingTimeline.empty().append(first);

        SubjectRegistryException regression = assertThrows(SubjectRegistryException.class, () -> timeline.append(
                mapping("019fcfea-4000-7000-8000-000000000018", aggregateId.toString(),
                        CARD_KEY, STUDENT_A, END, null, 1)));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT, regression.code());

        assertThrows(SubjectRegistryException.class, () -> mapping(
                "019fcfea-4000-7000-8000-000000000019", aggregateId.toString(),
                CARD_KEY, STUDENT_A, END, null, SubjectMapping.MAX_VERSION + 1));
    }

    @Test
    void exceptionStateMachineUsesOnlyControlledCodes() {
        SubjectMappingException open = SubjectMappingException.open(
                uuid("019fcfea-4000-7000-8000-000000000040"),
                CARD_KEY,
                MappingExceptionCode.AMBIGUOUS,
                "一卡通数据 owner",
                START);
        SubjectMappingException reviewing = open.beginReview(START.plusSeconds(1));
        SubjectMappingException resolved = reviewing.resolve(
                MappingResolutionCode.LINKED_TO_EXISTING_STUDENT,
                START.plusSeconds(2));

        assertEquals(MappingExceptionStatus.OPEN, open.status());
        assertEquals(MappingExceptionStatus.IN_REVIEW, reviewing.status());
        assertEquals(MappingExceptionStatus.RESOLVED, resolved.status());
        assertEquals(3, resolved.aggregateVersion());
        SubjectRegistryException invalid = assertThrows(
                SubjectRegistryException.class, () -> resolved.beginReview(START.plusSeconds(3)));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_INVALID_TRANSITION, invalid.code());
    }

    @Test
    void splitProjectionRetainsAllTargetsAndRequiresManualSelection() {
        SubjectLink split = SubjectLink.of(
                LinkType.SPLIT_INTO, STUDENT_A, List.of(STUDENT_B, studentRef(
                        "019fcfea-4000-7000-8000-000000000003")));
        SubjectLink alias = SubjectLink.of(LinkType.ALIAS, STUDENT_A, List.of(STUDENT_B));

        assertEquals(2, split.targets().size());
        assertTrue(split.manualSelectionRequired());
        assertFalse(alias.manualSelectionRequired());
        assertThrows(SubjectRegistryException.class, () -> SubjectLink.of(
                LinkType.MERGED_INTO, STUDENT_A, List.of(STUDENT_A)));
    }

    @Test
    void correctionLineageIsAppendOnlySequentialAndRejectsBranchesOrCycles() {
        UUID lineageId = uuid("019fcfea-4000-7000-8000-000000000050");
        MappingCorrectionEvent first = MappingCorrectionEvent.first(
                uuid("019fcfea-4000-7000-8000-000000000051"), lineageId,
                CorrectionType.SPLIT, SubjectLink.of(LinkType.SPLIT_INTO, STUDENT_A, List.of(STUDENT_B)),
                CorrectionReason.AUTHORITY_CORRECTION, START);
        MappingCorrectionLineage lineage = MappingCorrectionLineage.empty(lineageId).append(first);
        MappingCorrectionEvent second = MappingCorrectionEvent.next(
                uuid("019fcfea-4000-7000-8000-000000000052"), lineageId, first.eventId(), 2,
                CorrectionType.CORRECT, SubjectLink.of(LinkType.ALIAS, STUDENT_A, List.of(STUDENT_B)),
                CorrectionReason.AUTHORITY_CORRECTION, START.plusSeconds(1));

        MappingCorrectionLineage complete = lineage.append(second);
        assertEquals(second.eventId(), complete.currentEvent().orElseThrow().eventId());
        assertEquals(2, complete.events().size());

        SubjectRegistryException branch = assertThrows(SubjectRegistryException.class, () -> complete.append(
                MappingCorrectionEvent.next(
                        uuid("019fcfea-4000-7000-8000-000000000053"), lineageId, first.eventId(), 3,
                        CorrectionType.REVOKE, SubjectLink.of(LinkType.ALIAS, STUDENT_A, List.of()),
                        CorrectionReason.AUTHORITY_REVOCATION, START.plusSeconds(2))));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID, branch.code());
    }

    private static SubjectMapping mapping(
            String mappingId, String aggregateId, IdentifierKey key, StudentRef studentRef,
            Instant from, Instant to, long version) {
        return SubjectMapping.active(
                uuid(mappingId), uuid(aggregateId), key, studentRef,
                EffectiveInterval.of(from, to), version);
    }

    private static StudentRef studentRef(String value) {
        return StudentRef.of(uuid(value));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
