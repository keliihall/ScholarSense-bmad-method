package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContinuationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void consumesAnOpaqueContinuationExactlyOnceForTheBoundSessionAndOrigin() {
        FakeContinuationRepository repository = new FakeContinuationRepository();
        ContinuationService service = new ContinuationService(
                repository, () -> "ct_NjA2MzJkNzgtYjI1Yy00Mjc4LTk0MjQtNDUzNjQ4ZDc5YjI3",
                Clock.fixed(NOW, ZoneOffset.UTC));
        ContinuationCreated created = service.create(
                "browser-session-1", "https://app.stage.invalid", "shell.session", "opaque-001");

        ContinuationTarget target = service.consume(
                created.continuationCode(), "browser-session-1", "https://app.stage.invalid");
        assertEquals("shell.session", target.routeId());
        assertEquals("opaque-001", target.opaqueContext());

        IdentityAccessException replay = assertThrows(IdentityAccessException.class, () -> service.consume(
                created.continuationCode(), "browser-session-1", "https://app.stage.invalid"));
        assertEquals("CONTINUATION_INVALID_OR_EXPIRED", replay.code());
    }

    @Test
    void rejectsOpenRedirectCrossOriginUnknownBusinessAndExpiredTargetsWithOneSemantic() {
        for (String target : new String[] {
                "https://attacker.invalid/steal", "//attacker.invalid/steal", "/absolute-path", "work-item.unknown"
        }) {
            ContinuationService service = service(new FakeContinuationRepository(), NOW);
            IdentityAccessException error = assertThrows(IdentityAccessException.class, () -> service.create(
                    "browser-session-1", "https://app.stage.invalid", target, "opaque-001"));
            assertEquals("CONTINUATION_INVALID_OR_EXPIRED", error.code());
        }

        FakeContinuationRepository repository = new FakeContinuationRepository();
        ContinuationCreated created = service(repository, NOW).create(
                "browser-session-1", "https://app.stage.invalid", "shell.home", null);
        IdentityAccessException expired = assertThrows(IdentityAccessException.class, () ->
                service(repository, NOW.plusSeconds(301)).consume(
                        created.continuationCode(), "browser-session-1", "https://app.stage.invalid"));
        assertEquals("CONTINUATION_INVALID_OR_EXPIRED", expired.code());
    }

    private ContinuationService service(FakeContinuationRepository repository, Instant now) {
        return new ContinuationService(
                repository, () -> "ct_NjA2MzJkNzgtYjI1Yy00Mjc4LTk0MjQtNDUzNjQ4ZDc5YjI3",
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static final class FakeContinuationRepository implements ContinuationRepository {
        private final Map<String, StoredContinuation> values = new HashMap<>();

        @Override
        public void save(StoredContinuation value) {
            values.put(value.codeDigest(), value);
        }

        @Override
        public Optional<StoredContinuation> findByDigest(String digest) {
            return Optional.ofNullable(values.get(digest));
        }

        @Override
        public void markConsumed(String digest, Instant consumedAt) {
            StoredContinuation value = values.get(digest);
            values.put(digest, value.consume(consumedAt));
        }
    }
}
