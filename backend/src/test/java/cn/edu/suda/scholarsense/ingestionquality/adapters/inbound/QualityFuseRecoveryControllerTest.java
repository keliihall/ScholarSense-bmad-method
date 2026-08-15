package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContextQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalResult;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseRecoveryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryRequestState;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class QualityFuseRecoveryControllerTest {
    private static final UUID TASK = uuid("019fe8a0-7000-7000-8000-000000000801");
    private static final UUID REQUEST = uuid("019fe8a0-7000-7000-8000-000000000802");
    private static final UUID EPISODE = uuid("019fe8a0-7000-7000-8000-000000000803");
    private static final UUID ACCOUNT = uuid("019fe8a0-7000-7000-8000-000000000804");
    private static final UUID ORG = uuid("019fe8a0-7000-7000-8000-000000000805");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private QualityFuseRecoveryService recovery;
    private QualityRecoveryFinalizationService finalization;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        recovery = mock(QualityFuseRecoveryService.class);
        finalization = mock(QualityRecoveryFinalizationService.class);
        InternalSessionIdentityPort sessions = mock(InternalSessionIdentityPort.class);
        AuthoritativeIdentityContextQueryPort identities =
                mock(AuthoritativeIdentityContextQueryPort.class);
        CurrentNaturalPersonPrincipalQueryPort naturalPersons =
                mock(CurrentNaturalPersonPrincipalQueryPort.class);
        when(sessions.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, "session-pseudonym", "actor-pseudonym", 7,
                Instant.parse("2026-08-13T01:00:00Z"),
                Instant.parse("2026-08-13T00:55:00Z"), "ISP-1.0.0"));
        when(identities.findCurrent("actor-pseudonym")).thenReturn(Optional.of(
                new AuthoritativeIdentityContext(ACCOUNT, List.of("R6-DATA-OWNER"),
                        List.of(ORG), 2, 2, 2, IdentityFreshness.FRESH,
                        Map.of("identitySessionPolicy", "ISP-1.0.0",
                                "roleFieldPolicy", "RFP-1.0.0",
                                "roleMapping", "IAM-RM-1.0.0",
                                "roleMappingDigest", digest('a')),
                        Instant.parse("2026-08-13T00:00:00Z"))));
        when(naturalPersons.resolve(any())).thenReturn(
                CurrentNaturalPersonPrincipalResult.available(
                        digest('b'), 3, digest('c'), TRACE));
        mvc = MockMvcBuilders.standaloneSetup(
                        new QualityFuseRecoveryController(
                                recovery, sessions, identities, naturalPersons, finalization))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().enable(
                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .setControllerAdvice(new DataSourceCatalogExceptionHandler()).build();
    }

    @Test
    void finalApprovalCommandIsStrictAndNeverAcceptsTrustedLeaseOrReceipt() throws Exception {
        QualityRecoveryFinalizationContext context =
                mock(QualityRecoveryFinalizationContext.class);
        when(context.recoveryId()).thenReturn(REQUEST);
        when(context.recoveryVersion()).thenReturn(5L);
        when(context.taskId()).thenReturn(TASK);
        when(context.taskVersion()).thenReturn(2L);
        when(context.observationStatus()).thenReturn("ready");
        when(context.finalizationState()).thenReturn("approval-pending");
        when(context.approvalId()).thenReturn(uuid(
                "019fe8a0-7000-7000-8000-000000000811"));
        when(context.approvalVersion()).thenReturn(1L);
        when(context.policyVersion()).thenReturn("QRP-1.0.0");
        when(context.finalPreviewDigest()).thenReturn(digest('d'));
        when(context.observationDecisionDigest()).thenReturn(digest('e'));
        when(context.finalObservationWatermark()).thenReturn("wm-final");
        when(context.traceId()).thenReturn(TRACE);
        when(finalization.requestApproval(any(), any(), anyString())).thenReturn(context);
        String valid = """
                {"expectedRecoveryVersion":5,"expectedTaskVersion":2,
                 "finalObservationWatermark":"wm-final"}
                """;

        mvc.perform(post("/api/v1/quality-recovery-requests/{id}/final-approval-requests",
                        REQUEST).session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "final-approval-one")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.finalizationState").value("approval-pending"))
                .andExpect(jsonPath("$.approvalReceiptDigest").doesNotExist())
                .andExpect(jsonPath("$.executionJti").doesNotExist());

        mvc.perform(post("/api/v1/quality-recovery-requests/{id}/final-approval-requests",
                        REQUEST).session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "final-approval-two")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid.substring(0, valid.lastIndexOf('}'))
                                + ",\"leaseId\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));
    }

    @Test
    void makerRequestIsStrictNoStoreAndNeverReturnsApprovalSecrets() throws Exception {
        when(recovery.request(any(), anyLong(), anyString(), anyString(), any(), anyString()))
                .thenReturn(state("validating"));

        mvc.perform(post("/api/v1/quality-recovery-tasks/{id}/recovery-requests", TASK)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "request-one")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedTaskVersion":7,
                                 "reasonCode":"QUALITY_EVIDENCE_REVALIDATION_REQUESTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.status").value("validating"))
                .andExpect(jsonPath("$.approvalReceiptDigest").doesNotExist())
                .andExpect(jsonPath("$.executionJti").doesNotExist());

        mvc.perform(post("/api/v1/quality-recovery-tasks/{id}/recovery-requests", TASK)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "request-two")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedTaskVersion":7,
                                 "reasonCode":"QUALITY_EVIDENCE_REVALIDATION_REQUESTED",
                                 "approvalReceiptDigest":"secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));
    }

    @Test
    void approvalDecisionRequiresAnIdempotencyKeyAndPassesItToTheOwnerFlow()
            throws Exception {
        when(recovery.decide(any(), anyLong(), anyLong(), anyString(), anyString(), any(), anyString()))
                .thenReturn(state("approval-approved"));
        String body = """
                {"expectedVersion":4,"expectedApprovalVersion":2,"decision":"approve"}
                """;

        mvc.perform(post("/api/v1/quality-recovery-requests/{id}/approval-decisions", REQUEST)
                        .session(session()).header("Traceparent", traceparent())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/quality-recovery-requests/{id}/approval-decisions", REQUEST)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "checker-decision-one")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approval-approved"));

        verify(recovery).decide(eq(REQUEST), eq(4L), eq(2L), eq("approve"),
                eq("checker-decision-one"), any(), eq(TRACE));
    }

    @Test
    void concealedConflictUnprocessableAndUnavailableErrorsUseClosedEnvelopes()
            throws Exception {
        when(recovery.status(eq(REQUEST), any(), anyString())).thenThrow(
                new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN"));
        mvc.perform(get("/api/v1/quality-recovery-requests/{id}", REQUEST)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"));

        when(recovery.request(any(), anyLong(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_VERSION_CONFLICT", 9));
        mvc.perform(post("/api/v1/quality-recovery-tasks/{id}/recovery-requests", TASK)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "version-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedTaskVersion":7,
                                 "reasonCode":"QUALITY_EVIDENCE_REVALIDATION_REQUESTED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(9));

        when(recovery.requestApproval(any(), anyLong(), anyString(), any(), anyString()))
                .thenThrow(new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_INVALID_STATE"));
        mvc.perform(post("/api/v1/quality-recovery-requests/{id}/approval-requests", REQUEST)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "invalid-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors").isArray());

        when(recovery.execute(any(), anyLong(), anyString(), any(), anyString()))
                .thenThrow(new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));
        mvc.perform(post("/api/v1/quality-recovery-requests/{id}/execute", REQUEST)
                        .session(session()).header("Traceparent", traceparent())
                        .header("Idempotency-Key", "unavailable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));
    }

    private static QualityRecoveryRequestState state(String status) {
        boolean approved = status.equals("approval-approved");
        return new QualityRecoveryRequestState(REQUEST, approved ? 5 : 2, TASK, EPISODE,
                status, uuid("019fe8a0-7000-7000-8000-000000000806"),
                approved ? "succeeded" : "queued", approved ? digest('b') : null,
                approved ? uuid("019fe8a0-7000-7000-8000-000000000807") : null,
                approved ? 1L : null, approved ? digest('c') : null,
                approved ? Instant.parse("2026-08-13T00:15:00Z") : null, null,
                approved ? uuid("019fe8a0-7000-7000-8000-000000000808") : null,
                approved ? 3L : null, approved ? "approved" : null, TRACE, Map.of());
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, "opaque-session-id");
    }
    private static String traceparent() {
        return "00-" + TRACE + "-0011223344556677-01";
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static String digest(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
}
