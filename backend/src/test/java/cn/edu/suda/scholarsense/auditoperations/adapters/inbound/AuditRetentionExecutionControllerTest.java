package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchException;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionEvidenceView;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionReadService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditRetentionExecutionControllerTest {
    private static final String ID = "019d2c7d-4000-7000-8000-000000000015";
    private RetentionExecutionReadService reads;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reads = mock(RetentionExecutionReadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuditRetentionExecutionController(reads))
                .setControllerAdvice(new AuditSearchExceptionHandler()).build();
    }

    @Test
    void evidenceIsReadOnlyNoStoreAndHasNoArchiveLink() throws Exception {
        when(reads.read(any(), any(), anyString(), anyString())).thenReturn(
                new RetentionExecutionEvidenceView(Map.of("executionId", ID, "state", "succeeded")));

        mvc.perform(get("/api/v1/audit-retention-executions/{executionId}", ID)
                        .queryParam("view", "technical").principal(() -> "r7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.fields.state").value("succeeded"))
                .andExpect(jsonPath("$.fields.archiveObjectUrl").doesNotExist());
    }

    @Test
    void missingAndDeniedUseTheSameSafeEnvelope() throws Exception {
        when(reads.read(any(), any(), anyString(), anyString()))
                .thenThrow(new AuditSearchException("AUDIT_EVIDENCE_NOT_AVAILABLE"));
        mvc.perform(get("/api/v1/audit-retention-executions/{executionId}", ID)
                        .principal(() -> "r3"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUDIT_EVIDENCE_NOT_AVAILABLE"));
    }
}
