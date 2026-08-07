package cn.edu.suda.scholarsense.subjectregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class StudentRefIssuanceTest {

    @ParameterizedTest
    @MethodSource("decisions")
    void followsTheApprovedExactAuthorityDecisionTable(
            AuthorityEvidence evidence, StudentRefIssuanceDecision expected) {
        assertEquals(expected, StudentRefIssuer.decide(evidence));
    }

    static java.util.stream.Stream<Arguments> decisions() {
        return java.util.stream.Stream.of(
                Arguments.of(evidence("SRC-P0-STUDENT-001", 1, true, false, true),
                        StudentRefIssuanceDecision.ISSUE_STUDENT_REF),
                Arguments.of(evidence("SRC-P0-CARD-001", 0, false, false, false),
                        StudentRefIssuanceDecision.ISOLATE_NO_MATCH),
                Arguments.of(evidence("SRC-P0-DORM-ACCESS-001", 2, false, false, false),
                        StudentRefIssuanceDecision.ISOLATE_AMBIGUOUS),
                Arguments.of(evidence("SRC-P0-CARD-001", 1, false, true, false),
                        StudentRefIssuanceDecision.ISOLATE_REISSUE_UNPROVEN),
                Arguments.of(evidence("SRC-P1-NETWORK-001", 1, false, false, true),
                        StudentRefIssuanceDecision.RESOLVE_EXISTING_ONLY));
    }

    @Test
    void onlyApprovedAuthorityCanIssueAndAReservedStudentRefIsNeverReused() {
        AuthorityEvidence authority = evidence("SRC-P0-STUDENT-001", 1, true, false, true);
        StudentRef candidate = StudentRef.of(
                UUID.fromString("019fcfea-4000-7000-8000-000000000060"));
        assertEquals(candidate, StudentRefIssuer.issue(authority, candidate, Set.of()));

        SubjectRegistryException reused = assertThrows(
                SubjectRegistryException.class,
                () -> StudentRefIssuer.issue(authority, candidate, Set.of(candidate)));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_STUDENT_REF_REUSE, reused.code());

        SubjectRegistryException nonAuthority = assertThrows(
                SubjectRegistryException.class,
                () -> StudentRefIssuer.issue(
                        evidence("SRC-P0-CARD-001", 1, false, false, true), candidate, Set.of()));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_AUTHORITY_PROOF_REQUIRED, nonAuthority.code());
    }

    @Test
    void studentRefMustBeRfc9562UuidV7() {
        SubjectRegistryException invalid = assertThrows(
                SubjectRegistryException.class,
                () -> StudentRef.of(UUID.fromString("00000000-0000-4000-8000-000000000001")));
        assertEquals(SubjectRegistryErrorCode.SUBJECT_REGISTRY_STUDENT_REF_INVALID, invalid.code());
    }

    private static AuthorityEvidence evidence(
            String sourceId, int candidateCount, boolean approved, boolean previouslyIssued,
            boolean continuityProof) {
        return new AuthorityEvidence(
                sourceId, IdentifierType.STUDENT_NUMBER, candidateCount,
                approved, previouslyIssued, continuityProof);
    }
}
