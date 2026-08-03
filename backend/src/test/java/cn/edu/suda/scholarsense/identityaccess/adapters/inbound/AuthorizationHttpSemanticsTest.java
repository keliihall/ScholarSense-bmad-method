package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AuthorizationHttpSemanticsTest {
    @Test
    void forbiddenAndNonexistentObjectsHaveByteEquivalentSafe404Envelopes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new IdentityExceptionHandler())
                .build();

        var forbidden = mvc.perform(get("/fixture/forbidden").header(
                        "Traceparent",
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var missing = mvc.perform(get("/fixture/missing").header(
                        "Traceparent",
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(forbidden, missing);
    }

    @Test
    void surfaceDependencyAndStaleUseTheFrozenStatusesAndMessages() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new IdentityExceptionHandler())
                .build();

        mvc.perform(get("/fixture/surface"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN"));
        mvc.perform(get("/fixture/dependency"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE"));
        mvc.perform(get("/fixture/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_DECISION_STALE"));
    }

    @RestController
    static final class FixtureController {
        @GetMapping("/fixture/forbidden")
        void forbidden() {
            throw unavailable();
        }

        @GetMapping("/fixture/missing")
        void missing() {
            throw unavailable();
        }

        @GetMapping("/fixture/surface")
        void surface() {
            throw failure("IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN");
        }

        @GetMapping("/fixture/dependency")
        void dependency() {
            throw failure("IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE");
        }

        @GetMapping("/fixture/stale")
        void stale() {
            throw failure("IDENTITY_AUTHORIZATION_DECISION_STALE");
        }

        private static IdentityAccessException unavailable() {
            return failure("IDENTITY_AUTHORIZATION_OBJECT_UNAVAILABLE");
        }

        private static IdentityAccessException failure(String code) {
            return new IdentityAccessException(code, "raw-internal-object-detail");
        }
    }
}
