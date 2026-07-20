package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.identityaccess.application.SessionRefreshService;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdentityRefreshControllerTest {
    @Test
    void missingHttpSessionIsAuditedBeforeTheStableRejectionIsReturned() {
        SessionRefreshService service = mock(SessionRefreshService.class);
        var controller = new IdentityRefreshController(service, "school-idp");
        var request = new MockHttpServletRequest("POST", "/api/v1/identity-session-refreshes");
        var response = new MockHttpServletResponse();
        request.setRemoteAddr("192.0.2.10");

        IdentityAccessException failure = assertThrows(IdentityAccessException.class,
                () -> controller.refresh(
                        new IdentityRefreshController.RefreshRequest(1), request, response));

        assertEquals("IDENTITY_SESSION_REQUIRED", failure.code());
        verify(service).rejectMissingSession(anyString(), anyString());
    }

    @Test
    void ambiguousRefreshFailureInvalidatesTheOriginalBrowserSessionAndClearsItsCookie() {
        SessionRefreshService service = mock(SessionRefreshService.class);
        when(service.refresh(any())).thenThrow(new IdentityAccessException(
                "IDENTITY_REAUTHENTICATION_REQUIRED", "session recovery requires authentication"));
        var controller = new IdentityRefreshController(service, "school-idp");
        var request = new MockHttpServletRequest("POST", "/api/v1/identity-session-refreshes");
        request.setRemoteAddr("192.0.2.10");
        request.getSession(true);
        var response = new MockHttpServletResponse();

        IdentityAccessException failure = assertThrows(IdentityAccessException.class,
                () -> controller.refresh(
                        new IdentityRefreshController.RefreshRequest(1), request, response));

        assertEquals("IDENTITY_REAUTHENTICATION_REQUIRED", failure.code());
        assertEquals(null, request.getSession(false));
        String clearedCookie = response.getHeader("Set-Cookie");
        assertTrue(clearedCookie.startsWith("__Host-ScholarSense=; Path=/; Max-Age=0;"));
        assertTrue(clearedCookie.contains("Secure"));
        assertTrue(clearedCookie.contains("HttpOnly"));
        assertTrue(clearedCookie.contains("SameSite=Lax"));
    }
}
