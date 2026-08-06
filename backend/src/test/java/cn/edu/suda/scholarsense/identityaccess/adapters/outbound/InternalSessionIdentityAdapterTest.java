package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionProjection;
import cn.edu.suda.scholarsense.identityaccess.application.InternalSessionProjection;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionService;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InternalSessionIdentityAdapterTest {
    private static final String INTERNAL_SESSION_ID = "opaque-http-session-id";
    private static final String SOURCE_IP = "192.0.2.10";
    private static final String TRACE_ID = "00112233445566778899aabbccddeeff";

    @Test
    void resolvesTheServerOwnedInternalSessionToItsPseudonym() {
        CurrentSessionService sessions = mock(CurrentSessionService.class);
        when(sessions.currentInternal(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID)).thenReturn(
                new InternalSessionProjection(new CurrentSessionProjection(
                        true, "sp_RWxQcW41M2dSeHVIZ0JpYw", 7,
                        Instant.parse("2026-08-05T01:00:00Z"),
                        Instant.parse("2026-08-05T00:55:00Z"), "ISP-1.0.0"),
                        "stable-actor-pseudonym"));
        InternalSessionIdentityAdapter adapter = new InternalSessionIdentityAdapter(sessions);

        var current = adapter.current(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID);

        assertEquals("sp_RWxQcW41M2dSeHVIZ0JpYw", current.sessionPseudonym());
        assertEquals("stable-actor-pseudonym", current.actorPseudonym());
        assertEquals(7, current.sessionVersion());
        verify(sessions).currentInternal(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID);
    }

    @Test
    void preservesRequiredExpiredAndDependencyUnavailableAsTypedPublicFailures() {
        assertReason("IDENTITY_SESSION_REQUIRED",
                InternalSessionIdentityException.Reason.SESSION_REQUIRED);
        assertReason("IDENTITY_SESSION_EXPIRED",
                InternalSessionIdentityException.Reason.SESSION_EXPIRED);
        assertReason("IDENTITY_DEPENDENCY_UNAVAILABLE",
                InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE);
        assertReason("IDENTITY_AUDIT_UNAVAILABLE",
                InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void unexpectedRepositoryFailureIsDependencyUnavailableRatherThanForbidden() {
        CurrentSessionService sessions = mock(CurrentSessionService.class);
        when(sessions.currentInternal(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID))
                .thenThrow(new IllegalStateException("database unavailable"));
        InternalSessionIdentityAdapter adapter = new InternalSessionIdentityAdapter(sessions);

        InternalSessionIdentityException failure = assertThrows(
                InternalSessionIdentityException.class,
                () -> adapter.current(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID));

        assertEquals(
                InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE,
                failure.reason());
    }

    private static void assertReason(
            String code, InternalSessionIdentityException.Reason expected) {
        CurrentSessionService sessions = mock(CurrentSessionService.class);
        when(sessions.currentInternal(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID))
                .thenThrow(new IdentityAccessException(code, "stable test failure"));
        InternalSessionIdentityAdapter adapter = new InternalSessionIdentityAdapter(sessions);

        InternalSessionIdentityException failure = assertThrows(
                InternalSessionIdentityException.class,
                () -> adapter.current(INTERNAL_SESSION_ID, SOURCE_IP, TRACE_ID));

        assertEquals(expected, failure.reason());
    }
}
