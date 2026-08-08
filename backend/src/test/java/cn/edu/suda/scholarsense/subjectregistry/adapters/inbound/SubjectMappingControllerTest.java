package cn.edu.suda.scholarsense.subjectregistry.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairSubjectMappingCommand;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairSubjectMappingResult;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingExceptionView;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryApplicationException;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryService;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubjectMappingControllerTest {
    private static final UUID EXCEPTION_ID = uuid("019fcfea-6500-7000-8000-000000000001");
    private static final UUID SOURCE = uuid("019fcfea-6500-7000-8000-000000000002");
    private static final UUID TARGET = uuid("019fcfea-6500-7000-8000-000000000003");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private SubjectRegistryService service;
    private InternalSessionIdentityPort identities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SubjectRegistryService.class);
        identities = mock(InternalSessionIdentityPort.class);
        when(identities.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, "session-pseudonym", "actor-pseudonym", 7,
                Instant.parse("2026-08-06T07:00:00Z"),
                Instant.parse("2026-08-06T06:55:00Z"), "ISP-1.0.0"));
        mvc = MockMvcBuilders.standaloneSetup(new SubjectMappingController(service, identities))
                .setControllerAdvice(new SubjectMappingExceptionHandler()).build();
    }

    @Test
    void listIsExplicitlyPagedSevenFieldAndNeverCacheable() throws Exception {
        when(service.listExceptions(eq(0), eq(1), any(), any())).thenReturn(List.of(view(), view()));

        mvc.perform(get("/api/v1/subject-mapping-exceptions?page=0&size=1")
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("open"))
                .andExpect(jsonPath("$.items[0].subjectOfficialRef").value("ST0001"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void detailCarriesTheCasVersionOnlyAsANonCacheableEtag() throws Exception {
        when(service.detailException(any(), any(), any())).thenReturn(view());

        mvc.perform(get("/api/v1/subject-mapping-exceptions/{id}", EXCEPTION_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.aggregateVersion").doesNotExist())
                .andExpect(jsonPath("$.exceptionId").value(EXCEPTION_ID.toString()));
    }

    @Test
    void repairMapsTheApprovedWireCodesAndReturnsAcceptedJobHandle() throws Exception {
        when(service.repair(any())).thenReturn(new RepairSubjectMappingResult(
                EXCEPTION_ID, MappingExceptionStatus.RESOLVED, 3,
                uuid("019fcfea-6500-7000-8000-000000000010"),
                uuid("019fcfea-6500-7000-8000-000000000011")));

        mvc.perform(post("/api/v1/subject-mapping-exceptions/{id}/repair", EXCEPTION_ID)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "repair-idem-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedAggregateVersion":1,
                                 "reasonCode":"SUBJECT_MERGED",
                                 "sourceWatermark":"wm-42",
                                 "relationType":"merged-into",
                                 "sourceStudentRef":"%s",
                                 "targetStudentRefs":["%s"]}
                                """.formatted(SOURCE, TARGET)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.correctionId").isString())
                .andExpect(jsonPath("$.jobIds[0]").isString())
                .andExpect(jsonPath("$.traceId").value(TRACE));

        ArgumentCaptor<RepairSubjectMappingCommand> command =
                ArgumentCaptor.forClass(RepairSubjectMappingCommand.class);
        verify(service).repair(command.capture());
        assertEquals(SOURCE, command.getValue().subjectLink().source().value());
        assertEquals(TARGET, command.getValue().subjectLink().targets().getFirst().value());
        assertEquals("actor-pseudonym", command.getValue().actor().actorPseudonym());
    }

    @Test
    void absentAndForbiddenShare404AndConflictUsesStableEnvelope() throws Exception {
        when(service.detailException(any(), any(), any())).thenThrow(
                new SubjectRegistryApplicationException("SUBJECT_REGISTRY_FORBIDDEN"));
        mvc.perform(get("/api/v1/subject-mapping-exceptions/{id}", EXCEPTION_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REGISTRY_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Request could not be completed"))
                .andExpect(jsonPath("$.traceId").value(TRACE));

        when(service.repair(any())).thenThrow(new SubjectRegistryApplicationException(
                "SUBJECT_REGISTRY_VERSION_CONFLICT", 4L));
        mvc.perform(post("/api/v1/subject-mapping-exceptions/{id}/repair", EXCEPTION_ID)
                        .session(session()).header("Idempotency-Key", "repair-idem-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedAggregateVersion":1,
                                 "reasonCode":"AUTHORITY_CORRECTION",
                                 "sourceWatermark":"wm-42","relationType":"alias",
                                 "sourceStudentRef":"%s","targetStudentRefs":["%s"]}
                                """.formatted(SOURCE, TARGET)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentAggregateVersion").value(4));
    }

    @Test
    void invalidIdsAndMissingSessionFailBeforeServiceInvocation() throws Exception {
        mvc.perform(get("/api/v1/subject-mapping-exceptions/{id}", UUID.randomUUID())
                        .session(session()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/subject-mapping-exceptions/{id}", EXCEPTION_ID))
                .andExpect(status().isNotFound());
        verify(service, never()).detailException(any(), any(), any());
    }

    private static SubjectMappingExceptionView view() {
        return new SubjectMappingExceptionView(
                EXCEPTION_ID, MappingExceptionStatus.OPEN, Optional.of("ST0001"),
                MappingExceptionCode.AMBIGUOUS, "SRC-P0-CARD-001", "一卡通数据 owner",
                Instant.parse("2026-08-06T07:00:00Z"), 1);
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, "opaque-session-id");
    }

    private static String traceparent() {
        return "00-" + TRACE + "-0011223344556677-01";
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
