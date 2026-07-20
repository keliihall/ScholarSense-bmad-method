package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdentitySessionTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void authenticatesWithFrozenAccessIdleAbsoluteAndWarningWindows() {
        IdentitySession session = IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid",
                "family-1", "refresh-digest-1", NOW);

        assertEquals(NOW.plusSeconds(5 * 60), session.accessExpiresAt());
        assertEquals(NOW.plusSeconds(15 * 60), session.idleExpiresAt());
        assertEquals(NOW.plusSeconds(8 * 60 * 60), session.absoluteExpiresAt());
        assertEquals(NOW.plusSeconds(10 * 60), session.warningAt());
        assertEquals(1, session.sessionVersion());
        assertTrue(session.activeAt(NOW.plusSeconds(14 * 60)));
        assertFalse(session.activeAt(NOW.plusSeconds(15 * 60)));
    }

    @Test
    void rotatesRefreshOnceAndRevokesTheFamilyOnReuse() {
        IdentitySession initial = IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid",
                "family-1", "refresh-digest-1", NOW);
        RefreshRotation rotated = initial.rotateRefresh(
                "refresh-digest-1", "refresh-digest-2", 1, NOW.plusSeconds(120));

        assertFalse(rotated.reuseDetected());
        assertEquals(2, rotated.session().sessionVersion());
        assertEquals("refresh-digest-2", rotated.session().currentRefreshDigest());

        RefreshRotation replay = rotated.session().rotateRefresh(
                "refresh-digest-1", "refresh-digest-3", 2, NOW.plusSeconds(180));
        assertTrue(replay.reuseDetected());
        assertEquals(SessionStatus.REFRESH_FAMILY_REVOKED, replay.session().status());
        assertFalse(replay.session().activeAt(NOW.plusSeconds(181)));
    }

    @Test
    void staleSessionVersionFailsWithoutChangingTheSession() {
        IdentitySession session = IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid",
                "family-1", "refresh-digest-1", NOW);

        IdentityAccessException error = org.junit.jupiter.api.Assertions.assertThrows(
                IdentityAccessException.class,
                () -> session.revoke(2, NOW.plusSeconds(10)));
        assertEquals("IDENTITY_SESSION_VERSION_CONFLICT", error.code());
        assertEquals(SessionStatus.ACTIVE, session.status());
    }
}
