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

import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishCatalogCommand;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DataSourceCatalogControllerTest {
    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000011");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000012");
    private static final Principal R6 = () -> "owner-r6";
    private DataSourceCatalogService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DataSourceCatalogService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DataSourceCatalogController(service))
                .setControllerAdvice(new DataSourceCatalogExceptionHandler()).build();
    }

    @Test
    void listIsPagedAndNeverCacheable() throws Exception {
        when(service.list(20, 20, "owner-r6", "00112233445566778899aabbccddeeff"))
                .thenReturn(List.of());
        mvc.perform(get("/api/v1/data-source-catalogs?page=1&size=20")
                        .principal(R6).header("Traceparent", "00-00112233445566778899aabbccddeeff-0011223344556677-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void publicationRequiresIdempotencyAndDerivesRequestDigest() throws Exception {
        mvc.perform(post("/api/v1/data-source-catalogs/{id}/publications", CATALOG_ID)
                        .principal(R6).header("Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"catalogReleaseId":"%s",
                                 "evidenceSetDigest":"sha256:%s","requestedAt":"2026-08-04T20:00:00+08:00"}
                                """.formatted(RELEASE_ID, "d".repeat(64))))
                .andExpect(status().isOk());
        verify(service).publish(any(PublishCatalogCommand.class));
    }

    @Test
    void absentAndForbiddenShareTheSameNonDisclosureEnvelope() throws Exception {
        when(service.getDetail(any(), any(), any())).thenThrow(
                new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN"));
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID).principal(R6))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void rejectsNonV7IdsAndMissingPrincipalWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", UUID.randomUUID()).principal(R6))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID))
                .andExpect(status().isNotFound());
    }
}
