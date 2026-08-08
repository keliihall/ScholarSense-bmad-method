package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.RecomputeJobView;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectRecomputeJobQueryService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubjectRecomputeJobControllerTest {
    private static final UUID JOB = UUID.fromString("019fcfea-6600-7000-8000-000000000001");
    private SubjectRecomputeJobQueryService service;
    private InternalSessionIdentityPort identities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SubjectRecomputeJobQueryService.class);
        identities = mock(InternalSessionIdentityPort.class);
        when(identities.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, "session-pseudonym", "actor-pseudonym", 7,
                Instant.parse("2026-08-06T07:00:00Z"),
                Instant.parse("2026-08-06T06:55:00Z"), "ISP-1.0.0"));
        mvc = MockMvcBuilders.standaloneSetup(new SubjectRecomputeJobController(service, identities))
                .setControllerAdvice(new SubjectRecomputeJobExceptionHandler()).build();
    }

    @Test
    void authorizedJobUsesTheApprovedProjectionAndNoStore() throws Exception {
        when(service.get(any(), any(), any())).thenReturn(new RecomputeJobView(
                JOB, "running", 2, Instant.parse("2026-08-06T08:00:00Z"),
                null, null, "00112233445566778899aabbccddeeff"));
        mvc.perform(get("/api/v1/subject-recompute-jobs/{id}", JOB).session(session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.jobId").value(JOB.toString()))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.attemptNo").value(2));
        verify(service).get(any(), any(), any());
    }

    @Test
    void missingAndForbiddenAreTheSame404AndRandomUuidIsRejected() throws Exception {
        when(service.get(any(), any(), any())).thenThrow(
                new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN"));
        mvc.perform(get("/api/v1/subject-recompute-jobs/{id}", JOB).session(session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"));
        mvc.perform(get("/api/v1/subject-recompute-jobs/{id}", UUID.randomUUID()).session(session()))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, "opaque-session-id");
    }
}
