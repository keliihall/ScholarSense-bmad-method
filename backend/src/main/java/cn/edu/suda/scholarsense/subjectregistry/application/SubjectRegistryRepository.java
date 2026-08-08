package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingTimeline;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubjectRegistryRepository {
    List<StudentRef> findAuthorityCandidates(ProtectedIdentifierToken authorityToken, Instant at);
    boolean identifierPreviouslyIssued(IdentifierKey key);
    boolean isStudentRefReserved(StudentRef studentRef);
    SubjectMappingTimeline timeline(IdentifierKey key);
    Optional<SubjectMappingExceptionRecord> findException(UUID exceptionId);
    List<SubjectMappingExceptionRecord> listExceptions(int offset, int limit);
    void saveIngest(IngestCommit commit);
    RepairSubjectMappingResult saveRepair(RepairCommit commit, long expectedVersion);
}
