package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskView;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QualityRecoveryTaskControllerTest {
    private static final UUID TASK_ID = UUID.fromString(
            "019fe8a0-0000-7000-8000-000000000801");
    private static final UUID EPISODE_ID = UUID.fromString(
            "019fe8a0-0000-7000-8000-000000000802");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private QualityRecoveryTaskQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(QualityRecoveryTaskQueryService.class);
        InternalSessionIdentityPort identities = mock(InternalSessionIdentityPort.class);
        when(identities.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, "session-pseudonym", "actor-pseudonym", 7,
                Instant.parse("2026-08-10T01:00:00Z"),
                Instant.parse("2026-08-10T00:55:00Z"), "ISP-1.0.0"));
        mvc = MockMvcBuilders.standaloneSetup(
                        new QualityRecoveryTaskController(service, identities))
                .setControllerAdvice(new DataSourceCatalogExceptionHandler()).build();
    }

    @Test
    void listIsBoundedSourceFilteredReadOnlyAndNeverCached() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(List.of(view(), view()));

        mvc.perform(get("/api/v1/quality-recovery-tasks")
                        .session(session()).param("sourceId", "SRC-P0-CAMPUS-ACCESS-001")
                        .param("status", "open").param("size", "1")
                        .header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("open"))
                .andExpect(jsonPath("$.items[0].taskDelivery.status").value("pending"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor.taskId").value(TASK_ID.toString()));

        ArgumentCaptor<QualityRecoveryTaskQueryCriteria> criteria =
                ArgumentCaptor.forClass(QualityRecoveryTaskQueryCriteria.class);
        verify(service).list(criteria.capture(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(2, criteria.getValue().limit());
    }

    @Test
    void detailHasNoMutationOrRecoveryActivationRoute() throws Exception {
        when(service.get(any(), any(), any())).thenReturn(view());

        mvc.perform(get("/api/v1/quality-recovery-tasks/{id}", TASK_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.taskDelivery.target").value("public-task-platform"))
                .andExpect(jsonPath("$.taskDelivery.routeSequence").doesNotExist())
                .andExpect(jsonPath("$.aggregateVersion").doesNotExist())
                .andExpect(jsonPath("$.occurredAt").doesNotExist());

        mvc.perform(post("/api/v1/quality-recovery-tasks/{id}/recovery", TASK_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quality-recovery-tasks")
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void adviceConcealsDenialAndMapsDependencyFailureWithClosedNoStoreEnvelope()
            throws Exception {
        when(service.get(any(), any(), any())).thenThrow(
                new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN"));
        when(service.list(any(), any(), any())).thenThrow(
                new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));

        mvc.perform(get("/api/v1/quality-recovery-tasks/{id}", TASK_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.currentVersion").value(
                        org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        mvc.perform(get("/api/v1/quality-recovery-tasks")
                        .session(session()).param("sourceId", "SRC-P0-CAMPUS-ACCESS-001")
                        .header("Traceparent", traceparent()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));

        mvc.perform(get("/api/v1/quality-recovery-tasks")
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, "opaque-session-id");
    }

    private static String traceparent() {
        return "00-" + TRACE + "-0011223344556677-01";
    }

    private static QualityRecoveryTaskView view() {
        return new QualityRecoveryTaskView(
                TASK_ID, 1, EPISODE_ID, 1, "SRC-P0-CAMPUS-ACCESS-001",
                "DEP-P0-CAMPUS-ACCESS-001",
                List.of(new RuleVersionIdentity("ACC-SAFE-001", "1.0.0")),
                "source-owner:SRC-P0-CAMPUS-ACCESS-001", "P1",
                Instant.parse("2026-08-11T00:00:00Z"), "open", "opaque-watermark",
                Map.of("reasonCode", "REQUIRED_MEMBER_FUSED"),
                Map.of("qmdpVersion", "QMDP-1.0.0"), 1,
                Instant.parse("2026-08-10T00:00:00Z"),
                new QualityRecoveryTaskView.Delivery(
                        "public-task-platform", "pending", 0, null));
    }
}
