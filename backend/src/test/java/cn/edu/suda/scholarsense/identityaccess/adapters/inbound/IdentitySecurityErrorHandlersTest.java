package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class IdentitySecurityErrorHandlersTest {

    @Test
    void authenticationFailureUsesTheFrozenEnvelopeAndCode() throws Exception {
        var request = request();
        var response = new MockHttpServletResponse();

        IdentitySecurityErrorHandlers.authenticationEntryPoint().commence(
                request, response,
                new AuthenticationCredentialsNotFoundException("raw provider diagnostic"));

        assertEnvelope(response, 401);
    }

    @Test
    void accessDeniedAndCsrfFailuresUseTheSameFrozenEnvelope() throws Exception {
        var request = request();
        var response = new MockHttpServletResponse();
        SessionCommandService commands = mock(SessionCommandService.class);

        IdentitySecurityErrorHandlers.accessDeniedHandler(commands).handle(
                request, response, new AccessDeniedException("raw csrf diagnostic"));

        assertEnvelope(response, 403);
        verify(commands).auditAnonymousRejection(
                eq(SessionCommandType.LOGOUT), eq(null), anyString(), anyString());
    }

    @Test
    void reauthenticationRequiredRemainsAStableContractCode() throws Exception {
        var response = new MockHttpServletResponse();

        IdentityErrorResponseWriter.write(
                request(), response, 401, "IDENTITY_REAUTHENTICATION_REQUIRED");

        assertTrue(response.getContentAsString().contains(
                "\"code\":\"IDENTITY_REAUTHENTICATION_REQUIRED\""));
        assertTrue(response.getContentAsString().contains(
                "\"message\":\"authentication is required\""));
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/identity-sessions/logout");
        request.addHeader(
                "Traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        return request;
    }

    private static void assertEnvelope(MockHttpServletResponse response, int status) throws Exception {
        assertEquals(status, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":\"IDENTITY_SESSION_REQUIRED\""));
        assertTrue(body.contains("\"message\":\"authentication is required\""));
        assertTrue(body.contains("\"traceId\":\"0123456789abcdef0123456789abcdef\""));
        assertTrue(body.contains("\"fieldErrors\":[]"));
        assertFalse(body.contains("raw"));
    }
}
