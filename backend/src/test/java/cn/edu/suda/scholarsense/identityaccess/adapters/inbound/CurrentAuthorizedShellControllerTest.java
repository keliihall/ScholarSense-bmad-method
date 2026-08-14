package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.application.CurrentAuthorizedShellService;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.RolePackage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CurrentAuthorizedShellControllerTest {
    @Test
    void servesTheVersionedContractWithoutRolesOrReplayableAllowTokens() throws Exception {
        var projection = new CurrentAuthorizedShellService(RoleFieldPolicyCatalog.approved())
                .project(Set.of(RolePackage.R1), List.of(),
                        Instant.parse("2026-08-01T00:00:00Z"));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new CurrentAuthorizedShellController((session, trace, sourceIp) -> projection))
                .build();

        mvc.perform(get("/api/v1/authorized-shell")
                        .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                        .session(new MockHttpSession(null, "session-id")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.schemaVersion").value("AUTHORIZED-SHELL-1.1.0"))
                .andExpect(jsonPath("$.defaultSurface.surfaceId").value("care-workbench"))
                .andExpect(jsonPath("$.menuItems").isArray())
                .andExpect(jsonPath("$.actionCapabilities").isArray())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.allowToken").doesNotExist());
    }
}
