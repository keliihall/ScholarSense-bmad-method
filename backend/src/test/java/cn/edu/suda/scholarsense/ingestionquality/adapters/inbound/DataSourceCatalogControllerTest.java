package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogActorContext;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class DataSourceCatalogControllerTest {
    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000011");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000012");
    private static final String INTERNAL_SESSION_ID = "opaque-http-session-id";
    private static final String SESSION_PSEUDONYM = "sp_RWxQcW41M2dSeHVIZ0JpYw";
    private static final String ACTOR_PSEUDONYM = "identity-actor-pseudonym";
    private static final CatalogActorContext ACTOR = new CatalogActorContext(
            SESSION_PSEUDONYM, ACTOR_PSEUDONYM, "127.0.0.1");
    private static final Principal OIDC_PRINCIPAL = () -> "oidc-subject-is-not-a-session-pseudonym";
    private DataSourceCatalogService service;
    private InternalSessionIdentityPort identities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DataSourceCatalogService.class);
        identities = mock(InternalSessionIdentityPort.class);
        when(identities.current(any(), any(), any())).thenReturn(new InternalSessionIdentity(
                true, SESSION_PSEUDONYM, ACTOR_PSEUDONYM, 7,
                Instant.parse("2026-08-05T01:00:00Z"),
                Instant.parse("2026-08-05T00:55:00Z"), "ISP-1.0.0"));
        mvc = MockMvcBuilders.standaloneSetup(new DataSourceCatalogController(service, identities))
                .setControllerAdvice(new DataSourceCatalogExceptionHandler()).build();
    }

    @Test
    void listIsPagedAndNeverCacheable() throws Exception {
        when(service.list(1, 1, ACTOR, "00112233445566778899aabbccddeeff"))
                .thenReturn(List.of(view(CATALOG_ID), view(RELEASE_ID)));
        mvc.perform(get("/api/v1/data-source-catalogs?page=1&size=1")
                        .session(session()).principal(OIDC_PRINCIPAL)
                        .header("Traceparent", "00-00112233445566778899aabbccddeeff-0011223344556677-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));
        verify(identities).current(
                INTERNAL_SESSION_ID, "127.0.0.1", "00112233445566778899aabbccddeeff");
    }

    @Test
    void publicationRequiresIdempotencyAndDerivesRequestDigest() throws Exception {
        mvc.perform(post("/api/v1/data-source-catalogs/{id}/publications", CATALOG_ID)
                        .session(session()).principal(OIDC_PRINCIPAL)
                        .header("Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedCurrentVersion":4,
                                 "catalogReleaseId":"%s"}
                                """.formatted(RELEASE_ID)))
                .andExpect(status().isOk());
        ArgumentCaptor<PublishCatalogCommand> command = ArgumentCaptor.forClass(PublishCatalogCommand.class);
        verify(service).publish(command.capture());
        assertEquals(4, command.getValue().expectedCurrentVersion());
        assertEquals(RELEASE_ID, command.getValue().catalogReleaseId());
        assertEquals(SESSION_PSEUDONYM,
                command.getValue().actorContext().authorizationSessionRef());
        assertEquals(ACTOR_PSEUDONYM, command.getValue().actorContext().auditActorRef());
        assertEquals("127.0.0.1", command.getValue().actorContext().sourceIp());
    }

    @Test
    void arbitraryCatalogCreationEndpointDoesNotExist() throws Exception {
        mvc.perform(post("/api/v1/data-source-catalogs")
                        .session(session()).principal(OIDC_PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void absentAndForbiddenShareTheSameNonDisclosureEnvelope() throws Exception {
        when(service.getDetail(any(), any(), any())).thenThrow(
                new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN"));
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                        .session(session()).principal(OIDC_PRINCIPAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void rejectsNonV7IdsAndMissingHttpSessionWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", UUID.randomUUID())
                        .session(session()).principal(OIDC_PRINCIPAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                        .principal(OIDC_PRINCIPAL))
                .andExpect(status().isNotFound());
        verify(identities, never()).current(any(), any(), any());
    }

    @Test
    void expiredSessionIsForbiddenButIdentityDependencyFailureRemainsUnavailable() throws Exception {
        doThrow(
                new InternalSessionIdentityException(
                        InternalSessionIdentityException.Reason.SESSION_EXPIRED))
                .when(identities).current(any(), any(), any());
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                        .session(session()).principal(OIDC_PRINCIPAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"));

        doThrow(
                new InternalSessionIdentityException(
                        InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE))
                .when(identities).current(any(), any(), any());
        mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                        .session(session()).principal(OIDC_PRINCIPAL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void collectionConcealsMissingAndExpiredSessionsWithTheDetail404Envelope() throws Exception {
        String traceparent = "00-00112233445566778899aabbccddeeff-0011223344556677-01";
        var missingDetail = mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                        .principal(OIDC_PRINCIPAL).header("Traceparent", traceparent))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value("00112233445566778899aabbccddeeff"))
                .andReturn();
        var missingCollection = mvc.perform(get("/api/v1/data-source-catalogs")
                        .principal(OIDC_PRINCIPAL).header("Traceparent", traceparent))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value("00112233445566778899aabbccddeeff"))
                .andReturn();
        ObjectMapper json = new ObjectMapper();
        assertEquals(
                json.readTree(missingDetail.getResponse().getContentAsString()),
                json.readTree(missingCollection.getResponse().getContentAsString()));

        doThrow(new InternalSessionIdentityException(
                InternalSessionIdentityException.Reason.SESSION_EXPIRED))
                .when(identities).current(any(), any(), any());
        var expiredDetail = mvc.perform(get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                        .session(session()).principal(OIDC_PRINCIPAL)
                        .header("Traceparent", traceparent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andReturn();
        var expiredCollection = mvc.perform(get("/api/v1/data-source-catalogs")
                        .session(session()).principal(OIDC_PRINCIPAL)
                        .header("Traceparent", traceparent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_FORBIDDEN"))
                .andReturn();
        assertEquals(
                json.readTree(expiredDetail.getResponse().getContentAsString()),
                json.readTree(expiredCollection.getResponse().getContentAsString()));
    }

    @Test
    void springBindingFailuresAndPageOverflowUseTheStableRequestEnvelope() throws Exception {
        String traceparent = "00-00112233445566778899aabbccddeeff-0011223344556677-01";
        mvc.perform(post("/api/v1/data-source-catalogs/{id}/publications", CATALOG_ID)
                        .session(session()).header("Traceparent", traceparent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedCurrentVersion":0,
                                 "catalogReleaseId":"%s"}
                                """.formatted(RELEASE_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"))
                .andExpect(jsonPath("$.traceId").value("00112233445566778899aabbccddeeff"));

        mvc.perform(get("/api/v1/data-source-catalogs/not-a-uuid")
                        .session(session()).header("Traceparent", traceparent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));

        mvc.perform(post("/api/v1/data-source-catalogs/{id}/validations", CATALOG_ID)
                        .session(session()).header("Traceparent", traceparent)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));

        mvc.perform(get("/api/v1/data-source-catalogs?page=2147483647&size=100")
                        .session(session()).header("Traceparent", traceparent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));

        mvc.perform(post("/api/v1/data-source-catalogs/{id}/publications", CATALOG_ID)
                        .session(session()).header("Traceparent", traceparent)
                        .header("Idempotency-Key", "idem-version-overflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":9007199254740992,
                                 "expectedCurrentVersion":0,
                                 "catalogReleaseId":"%s"}
                                """.formatted(RELEASE_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGESTION_QUALITY_REQUEST_INVALID"));
    }

    @Test
    void generatedTraceIsReusedByServiceAndErrorEnvelopeWithoutAValidTraceparent()
            throws Exception {
        AtomicReference<String> serviceTrace = new AtomicReference<>();
        when(service.getDetail(any(), any(), any())).thenAnswer(invocation -> {
            serviceTrace.set(invocation.getArgument(2));
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
        });

        for (String traceparent : new String[]{null, "not-a-traceparent"}) {
            serviceTrace.set(null);
            var request = get("/api/v1/data-source-catalogs/{id}", CATALOG_ID)
                    .session(session());
            if (traceparent != null) {
                request.header("Traceparent", traceparent);
            }

            var result = mvc.perform(request)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(
                            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"))
                    .andReturn();
            String responseTrace = new ObjectMapper()
                    .readTree(result.getResponse().getContentAsString())
                    .required("traceId").asText();

            assertEquals(serviceTrace.get(), responseTrace);
            assertTrue(responseTrace.matches("(?!0{32})[0-9a-f]{32}"));
        }
    }

    private static CatalogView view(UUID id) {
        return new CatalogView(
                id, null, "DCC-1.0.0", CatalogStatus.DRAFT, 1, 0,
                "sha256:" + "a".repeat(64), null, List.of(),
                Instant.parse("2026-08-05T00:00:00Z"), null);
    }

    private static MockHttpSession session() {
        return new MockHttpSession(null, INTERNAL_SESSION_ID);
    }
}
