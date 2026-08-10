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
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotMetricView;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotQueryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotView;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QualitySnapshotControllerTest {
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000501");
    private static final UUID BATCH_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000502");
    private static final UUID LINEAGE_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000503");
    private static final String TRACE_ID = "00112233445566778899aabbccddeeff";
    private QualitySnapshotQueryService service;
    private InternalSessionIdentityPort identities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(QualitySnapshotQueryService.class);
        identities = mock(InternalSessionIdentityPort.class);
        when(identities.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, "session-pseudonym", "actor-pseudonym", 7,
                Instant.parse("2026-08-10T01:00:00Z"),
                Instant.parse("2026-08-10T00:55:00Z"), "ISP-1.0.0"));
        mvc = MockMvcBuilders.standaloneSetup(new QualitySnapshotController(service, identities))
                .setControllerAdvice(new DataSourceCatalogExceptionHandler()).build();
    }

    @Test
    void listUsesExplicitKeysetPaginationAndNeverCaches() throws Exception {
        QualitySnapshotView view = view();
        when(service.list(any(), any(), any())).thenReturn(List.of(view, view));

        mvc.perform(get("/api/v1/quality-snapshots")
                        .session(session())
                        .param("sourceId", "SRC-P0-STUDENT-001")
                        .param("overallResult", "quality-passed")
                        .param("evaluatedFrom", "2026-08-01T08:00:00+08:00")
                        .param("evaluatedTo", "2026-08-11T08:00:00+08:00")
                        .param("sortField", "evaluatedAt")
                        .param("sortDirection", "asc")
                        .param("size", "1")
                        .header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor.evaluatedAt").value("2026-08-10T00:00:00Z"))
                .andExpect(jsonPath("$.nextCursor.snapshotId").value(SNAPSHOT_ID.toString()));

        ArgumentCaptor<QualitySnapshotQueryCriteria> criteria =
                ArgumentCaptor.forClass(QualitySnapshotQueryCriteria.class);
        verify(service).list(criteria.capture(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(2, criteria.getValue().limit());
        org.junit.jupiter.api.Assertions.assertEquals(
                "evaluatedAt", criteria.getValue().sortField());
        org.junit.jupiter.api.Assertions.assertEquals(
                "asc", criteria.getValue().sortDirection());
        org.junit.jupiter.api.Assertions.assertEquals(
                Instant.parse("2026-08-01T00:00:00Z"), criteria.getValue().evaluatedFrom());
    }

    @Test
    void detailAndMetricExposeOnlyAssessedSnapshotRoutes() throws Exception {
        when(service.get(any(), any(), any())).thenReturn(view());
        when(service.getMetric(any(), any(), any(), any(), any(), any())).thenReturn(metric());

        mvc.perform(get("/api/v1/quality-snapshots/{id}", SNAPSHOT_ID)
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.snapshotId").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.assessedBatchStatus").value("quality-passed"))
                .andExpect(jsonPath("$.observationWindow.startAt").value("2026-08-09T00:00:00Z"));

        mvc.perform(get("/api/v1/quality-snapshots/{id}/metrics/{metricId}",
                        SNAPSHOT_ID, "PRIMARY_KEY_COMPLETENESS")
                        .param("formulaId", "QMDP-1.0.0/primary-key-completeness")
                        .param("formulaVersion", "1.0.0")
                        .session(session()).header("Traceparent", traceparent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricId").value("PRIMARY_KEY_COMPLETENESS"))
                .andExpect(jsonPath("$.numerator").value(100))
                .andExpect(jsonPath("$.denominator").value(100));

        mvc.perform(get("/api/v1/data-batches").session(session()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quality-snapshots/{id}/publish", SNAPSHOT_ID)
                        .session(session()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quality-snapshots/{id}/seal", SNAPSHOT_ID)
                        .session(session()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quality-snapshots/{id}/pass", SNAPSHOT_ID)
                        .session(session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedUuidCursorTimestampMetricAndMissingSessionFailWithoutServiceUse()
            throws Exception {
        mvc.perform(get("/api/v1/quality-snapshots/not-a-uuid").session(session()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots")
                        .session(session()).param("afterSnapshotId", SNAPSHOT_ID.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots")
                        .session(session()).param("evaluatedFrom", "2026-08-10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots")
                        .session(session()).param("sortField", "sourceId"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots")
                        .session(session()).param("sortDirection", "sideways"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots/{id}/metrics/{metricId}",
                        SNAPSHOT_ID, "student-name" ).session(session()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots/{id}/metrics/{metricId}",
                        SNAPSHOT_ID, "PRIMARY_KEY_COMPLETENESS").session(session()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quality-snapshots/{id}", SNAPSHOT_ID))
                .andExpect(status().isNotFound());
        verify(service, never()).get(any(), any(), any());
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, "opaque-session-id");
    }

    private static String traceparent() {
        return "00-" + TRACE_ID + "-0011223344556677-01";
    }

    private static QualitySnapshotView view() {
        return new QualitySnapshotView(
                SNAPSHOT_ID, BATCH_ID, "SRC-P0-STUDENT-001", "quality-passed",
                "quality-passed", new QualitySnapshotView.ObservationWindowView(
                        Instant.parse("2026-08-09T00:00:00Z"),
                        Instant.parse("2026-08-10T00:00:00Z")),
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:00:00Z"),
                "src-p0-student-001@2026-08-10", List.of(metric()),
                List.of("PRIMARY_KEY_COMPLETENESS"), "SRC-P0-STUDENT-001",
                "AUTH-2026-08-08-001", Instant.parse("2026-08-09T00:00:00Z"),
                "RS-1.0.0", "QMDP-1.0.0", "sha256:" + "1".repeat(64),
                "QG-1.0.0", "sha256:" + "2".repeat(64),
                "SCHOLARSENSE-CANONICAL-JSON-1.0.0", "sha256:" + "3".repeat(64),
                "SIS-1.0.0", "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64),
                TRACE_ID, LINEAGE_ID, null, 3);
    }

    private static QualitySnapshotMetricView metric() {
        return new QualitySnapshotMetricView(
                "PRIMARY_KEY_COMPLETENESS", "QMDP-1.0.0/primary-key-completeness",
                "1.0.0", "passed", true, BigInteger.valueOf(100), BigInteger.valueOf(100),
                BigInteger.valueOf(10000), "basis-point", ">=", BigInteger.valueOf(995),
                BigInteger.valueOf(1000), "inclusive", null);
    }
}
