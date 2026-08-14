package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
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
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QualityEligibilityControllerTest {
    private static final UUID ELIGIBILITY_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000801");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000802");
    private static final String TRACE_ID = "00112233445566778899aabbccddeeff";
    private QualityEligibilityQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(QualityEligibilityQueryService.class);
        InternalSessionIdentityPort identities = mock(InternalSessionIdentityPort.class);
        when(identities.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, "session-pseudonym", "actor-pseudonym", 7,
                Instant.parse("2026-08-10T01:00:00Z"),
                Instant.parse("2026-08-10T00:55:00Z"), "ISP-1.0.0"));
        mvc = MockMvcBuilders.standaloneSetup(
                        new QualityEligibilityController(service, identities))
                .setControllerAdvice(new DataSourceCatalogExceptionHandler()).build();
    }

    @Test
    void listUsesBoundedKeysetPaginationAndNeverCaches() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(List.of(view(), view()));

        mvc.perform(get("/api/v1/quality-eligibilities")
                        .session(session()).param("status", "fused")
                        .param("ruleId", "ACC-SAFE-001").param("size", "1")
                        .header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("fused"))
                .andExpect(jsonPath("$.items[0].members[0].dependencyId")
                        .value("DEP-P0-DORM-ACCESS-001"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor.eligibilityId")
                        .value(ELIGIBILITY_ID.toString()));

        ArgumentCaptor<QualityEligibilityQueryCriteria> criteria =
                ArgumentCaptor.forClass(QualityEligibilityQueryCriteria.class);
        verify(service).list(criteria.capture(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(2, criteria.getValue().limit());
    }

    @Test
    void detailIsReadOnlyAndRecoveryActivationIsAbsent() throws Exception {
        when(service.get(any(), any(), any())).thenReturn(view());

        mvc.perform(get("/api/v1/quality-eligibilities/{id}", ELIGIBILITY_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eligibilityId").value(ELIGIBILITY_ID.toString()))
                .andExpect(jsonPath("$.failedMembers[0]")
                        .value("DEP-P0-DORM-ACCESS-001"));

        mvc.perform(post("/api/v1/quality-eligibilities/{id}/recovery", ELIGIBILITY_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedCursorUuidAndMissingSessionFailBeforeServiceUse() throws Exception {
        mvc.perform(get("/api/v1/quality-eligibilities")
                        .session(session()).param("afterEligibilityId", ELIGIBILITY_ID.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-eligibilities/not-a-uuid").session(session()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-eligibilities/{id}", ELIGIBILITY_ID))
                .andExpect(status().isNotFound());
        verify(service, never()).get(any(), any(), any());
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, "opaque-session-id");
    }

    private static String traceparent() {
        return "00-" + TRACE_ID + "-0011223344556677-01";
    }

    private static QualityEligibilityView view() {
        return new QualityEligibilityView(
                ELIGIBILITY_ID, "ACC-SAFE-001", "1.0.0", "fused",
                "REQUIRED_MEMBER_FUSED", "all-of", null,
                List.of(new QualityEligibilityView.Member(
                        "SRC-P0-DORM-ACCESS-001", 1, "DEP-P0-DORM-ACCESS-001", 1,
                        "required", "fused", true, "source-watermark",
                        "dependency-watermark", SNAPSHOT_ID, "sha256:" + "1".repeat(64))),
                List.of("DEP-P0-DORM-ACCESS-001"),
                "RULE-DEPENDENCY-REGISTRY-1.0.0", 1,
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:00:01Z"));
    }
}
