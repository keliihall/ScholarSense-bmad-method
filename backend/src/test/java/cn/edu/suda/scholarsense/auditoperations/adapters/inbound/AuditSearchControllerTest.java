package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchException;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchPage;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchService;
import cn.edu.suda.scholarsense.auditoperations.application.ProjectedAuditRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditSearchControllerTest {
    private AuditSearchService searches;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        searches = mock(AuditSearchService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuditSearchController(searches))
                .setControllerAdvice(new AuditSearchExceptionHandler())
                .build();
    }

    @Test
    void postSearchReturnsOnlyProjectedBodyWithNoStoreAndNoReferrer() throws Exception {
        when(searches.search(any())).thenReturn(new AuditSearchPage(
                List.of(new ProjectedAuditRecord(Map.of(
                        "recordId", "019d2c7d-4000-7000-8000-000000000042",
                        "ledgerSequence", 42))),
                0, 25, 1, 42, 44, 42, Instant.parse("2026-07-23T00:00:00Z"),
                "RS-1.0.0", "RFP-1.0.0", "degraded"));

        var response = mvc.perform(post("/api/v1/audit-records/search")
                        .principal(() -> "r3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"view":"business","actorRef":"sensitive-actor",\
                                 "page":0,"size":25}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.items[0].fields.ledgerSequence").value(42))
                .andReturn().getResponse();

        assertFalse(response.getContentAsString().contains("sensitive-actor"));
    }

    @Test
    void forbiddenAndInvalidRequestsUseSafeEnvelopesAndNoStore() throws Exception {
        when(searches.search(any())).thenThrow(new AuditSearchException("AUDIT_SEARCH_FORBIDDEN"));
        mvc.perform(post("/api/v1/audit-records/search")
                        .principal(() -> "other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":\"business\",\"page\":0,\"size\":25}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("AUDIT_SEARCH_FORBIDDEN"))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        mvc.perform(post("/api/v1/audit-records/search")
                        .principal(() -> "r3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":\"business\",\"page\":0,\"size\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIT_SEARCH_INVALID_REQUEST"));
    }

    @Test
    void nonJsonContentTypeCannotReachTheSearchContract() throws Exception {
        mvc.perform(post("/api/v1/audit-records/search")
                        .principal(() -> "r3")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("sensitive-actor"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
