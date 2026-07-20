package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.RequestOriginPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestOriginValidationFilterTest {
    private final RequestOriginValidationFilter filter = new RequestOriginValidationFilter(
            new RequestOriginPolicy("https://app.stage.invalid"));

    @Test
    void permitsExactOriginAndThenLetsCsrfFilterDecideTheToken() throws Exception {
        var request = request("POST");
        request.addHeader("Origin", "https://app.stage.invalid");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> called.set(true));

        assertTrue(called.get());
    }

    @Test
    void rejectsLookalikeOriginWithoutReflectingItIntoTheStableEnvelope() throws Exception {
        var request = request("DELETE");
        request.addHeader("Origin", "https://app.stage.invalid.attacker.example");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> called.set(true));

        assertFalse(called.get());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDENTITY_SESSION_REQUIRED"));
        assertFalse(response.getContentAsString().contains("attacker.example"));
    }

    private static MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/v1/identity-sessions/logout");
        request.setSecure(true);
        return request;
    }
}
