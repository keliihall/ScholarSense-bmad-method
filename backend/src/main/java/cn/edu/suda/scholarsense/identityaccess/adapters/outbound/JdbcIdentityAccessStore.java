package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AuthorizedClientSecretRepository;
import cn.edu.suda.scholarsense.identityaccess.application.ContinuationRepository;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedAuthorizedClient;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapRepository;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutOutboxPort;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutWorkRepository;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutRequest;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandResult;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import cn.edu.suda.scholarsense.identityaccess.application.SessionIdempotencyRepository;
import cn.edu.suda.scholarsense.identityaccess.application.StoredContinuation;
import cn.edu.suda.scholarsense.identityaccess.application.StoredHostBootstrap;
import cn.edu.suda.scholarsense.identityaccess.application.StoredSessionCommand;
import cn.edu.suda.scholarsense.identityaccess.application.StoredRemoteLogout;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.identityaccess.domain.SessionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL shared correctness store. It never persists bearer-token plaintext. */
public final class JdbcIdentityAccessStore implements
        IdentitySessionRepository,
        SessionIdempotencyRepository,
        ContinuationRepository,
        HostBootstrapRepository,
        RemoteLogoutOutboxPort,
        RemoteLogoutWorkRepository,
        AuthorizedClientSecretRepository {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(8);
    private final JdbcTemplate jdbc;

    public JdbcIdentityAccessStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdentitySession> findById(String sessionId) {
        return jdbc.query("""
                select * from identity_access.ia_identity_session where session_id = ?
                """, JdbcIdentityAccessStore::mapSession, sessionId).stream().findFirst();
    }

    @Override
    public Optional<IdentitySession> findByIdForUpdate(String sessionId) {
        return jdbc.query("""
                select * from identity_access.ia_identity_session where session_id = ? for update
                """, JdbcIdentityAccessStore::mapSession, sessionId).stream().findFirst();
    }

    @Override
    public void save(IdentitySession value) {
        int updated = jdbc.update("""
                update identity_access.ia_identity_session set
                  session_pseudonym=?, actor_pseudonym=?, browser_binding_hash=?, origin=?, last_activity_at=?,
                  access_expires_at=?, idle_expires_at=?, absolute_expires_at=?, warning_at=?,
                  session_version=?, refresh_family_id=?, current_refresh_digest=?,
                  used_refresh_digests=?, status=?, updated_at=?
                where session_id=? and session_version < ?
                """,
                value.sessionPseudonym(), value.actorPseudonym(), value.browserBindingHash(), value.origin(),
                timestamp(value.lastActivityAt()), timestamp(value.accessExpiresAt()),
                timestamp(value.idleExpiresAt()), timestamp(value.absoluteExpiresAt()),
                timestamp(value.warningAt()), value.sessionVersion(), value.refreshFamilyId(),
                value.currentRefreshDigest(), String.join(",", value.usedRefreshDigests()),
                value.status().name(), timestamp(Instant.now()), value.sessionId(), value.sessionVersion());
        if (updated == 1) {
            return;
        }
        if (exists(value.sessionId())) {
            throw new IdentityAccessException(
                    "IDENTITY_SESSION_VERSION_CONFLICT", "session changed; refresh before retrying");
        }
        try {
            jdbc.update("""
                    insert into identity_access.ia_identity_session (
                      session_id, session_pseudonym, actor_pseudonym, browser_binding_hash, origin, created_at,
                      last_activity_at, access_expires_at, idle_expires_at, absolute_expires_at,
                      warning_at, session_version, refresh_family_id, current_refresh_digest,
                      used_refresh_digests, status, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.sessionId(), value.sessionPseudonym(), value.actorPseudonym(),
                    value.browserBindingHash(), value.origin(),
                    timestamp(value.createdAt()), timestamp(value.lastActivityAt()),
                    timestamp(value.accessExpiresAt()), timestamp(value.idleExpiresAt()),
                    timestamp(value.absoluteExpiresAt()), timestamp(value.warningAt()),
                    value.sessionVersion(), value.refreshFamilyId(), value.currentRefreshDigest(),
                    String.join(",", value.usedRefreshDigests()), value.status().name(), timestamp(Instant.now()));
        } catch (DuplicateKeyException conflict) {
            throw new IdentityAccessException(
                    "IDENTITY_SESSION_VERSION_CONFLICT", "session changed; refresh before retrying");
        }
    }

    @Override
    public Optional<StoredSessionCommand> find(String sessionId, SessionCommandType type, String key) {
        return jdbc.query("""
                select * from identity_access.ia_idempotency_result
                where session_id=? and command_type=? and idempotency_key=? and expires_at>?
                """, JdbcIdentityAccessStore::mapStoredSessionCommand,
                sessionId, type.name(), key, timestamp(Instant.now())).stream().findFirst();
    }

    @Override
    public Optional<StoredSessionCommand> find(SessionCommandType type, String key) {
        return jdbc.query("""
                select * from identity_access.ia_idempotency_result
                where command_type=? and idempotency_key=? and expires_at>?
                """, JdbcIdentityAccessStore::mapStoredSessionCommand,
                type.name(), key, timestamp(Instant.now())).stream().findFirst();
    }

    @Override
    public void save(StoredSessionCommand value) {
        var result = value.result();
        jdbc.update("""
                insert into identity_access.ia_idempotency_result (
                  session_id, command_type, idempotency_key, request_digest,
                  result_session_version, result_completed_at, result_session_pseudonym, result_next_action,
                  result_remote_pending, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.sessionId(), value.commandType().name(), value.idempotencyKey(),
                value.requestDigest(), result.sessionVersion(), timestamp(result.completedAt()),
                result.sessionPseudonym(), result.nextAction(), result.remoteRevocationPending(),
                timestamp(result.completedAt().plus(IDEMPOTENCY_RETENTION)));
    }

    @Override
    public void save(StoredContinuation value) {
        jdbc.update("""
                insert into identity_access.ia_continuation
                  (code_digest, browser_session_id, origin, route_id, opaque_context, expires_at, consumed_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, value.codeDigest(), value.browserSessionId(), value.origin(), value.routeId(),
                value.opaqueContext(), timestamp(value.expiresAt()), timestampOrNull(value.consumedAt()));
    }

    @Override
    public Optional<StoredContinuation> findByDigest(String digest) {
        return jdbc.query("""
                select * from identity_access.ia_continuation where code_digest=?
                """, (rs, row) -> new StoredContinuation(
                rs.getString("code_digest"), rs.getString("browser_session_id"),
                rs.getString("origin"), rs.getString("route_id"), rs.getString("opaque_context"),
                instant(rs, "expires_at"), nullableInstant(rs, "consumed_at")), digest)
                .stream().findFirst();
    }

    @Override
    public void markConsumed(String digest, Instant consumedAt) {
        if (jdbc.update("""
                update identity_access.ia_continuation set consumed_at=?
                where code_digest=? and consumed_at is null and expires_at>?
                """, timestamp(consumedAt), digest, timestamp(consumedAt)) != 1) {
            throw new IdentityAccessException(
                    "CONTINUATION_INVALID_OR_EXPIRED", "the requested destination is unavailable");
        }
    }

    @Override
    public Optional<StoredHostBootstrap> findBootstrapByDigest(String codeDigest) {
        return jdbc.query("""
                select * from identity_access.ia_host_bootstrap where code_digest=?
                """, (rs, row) -> new StoredHostBootstrap(
                rs.getString("code_digest"), rs.getString("audience"), rs.getString("origin"),
                rs.getString("browser_binding_hash"), instant(rs, "expires_at"),
                nullableInstant(rs, "consumed_at")), codeDigest).stream().findFirst();
    }

    @Override
    public void saveBootstrap(StoredHostBootstrap bootstrap) {
        jdbc.update("""
                insert into identity_access.ia_host_bootstrap
                  (code_digest, audience, origin, browser_binding_hash, expires_at, consumed_at)
                values (?, ?, ?, ?, ?, ?)
                """, bootstrap.codeDigest(), bootstrap.audience(), bootstrap.origin(),
                bootstrap.browserBindingHash(), timestamp(bootstrap.expiresAt()),
                timestampOrNull(bootstrap.consumedAt()));
    }

    @Override
    public boolean consumeOnce(String codeDigest, Instant consumedAt) {
        return jdbc.update("""
                update identity_access.ia_host_bootstrap set consumed_at=?
                where code_digest=? and consumed_at is null and expires_at>?
                """, timestamp(consumedAt), codeDigest, timestamp(consumedAt)) == 1;
    }

    @Override
    public void enqueue(RemoteLogoutRequest request) {
        jdbc.update("""
                insert into identity_access.ia_remote_logout_outbox (
                  request_id, session_id, registration_id, command_type, idempotency_key, state, attempts,
                  next_attempt_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, java.util.UUID.fromString(request.requestId()), request.sessionId(),
                request.registrationId(), request.commandType().name(), request.idempotencyKey(), request.state(),
                timestamp(request.createdAt()), timestamp(request.createdAt()), timestamp(request.createdAt()));
    }

    @Override
    public Optional<StoredRemoteLogout> claimDue(Instant now) {
        return jdbc.query("""
                with candidate as (
                  select request_id from identity_access.ia_remote_logout_outbox
                  where state in ('pending', 'retrying') and next_attempt_at<=?
                  order by next_attempt_at, request_id
                  for update skip locked limit 1
                )
                update identity_access.ia_remote_logout_outbox work
                set state='retrying', attempts=attempts+1, updated_at=?
                from candidate where work.request_id=candidate.request_id
                returning work.request_id, work.session_id, work.registration_id, work.command_type,
                          work.attempts, work.next_attempt_at
                """, (rs, row) -> new StoredRemoteLogout(
                rs.getString("request_id"), rs.getString("session_id"), rs.getString("registration_id"),
                SessionCommandType.valueOf(rs.getString("command_type")),
                rs.getInt("attempts"), instant(rs, "next_attempt_at")),
                timestamp(now), timestamp(now)).stream().findFirst();
    }

    @Override
    public void markConfirmed(String requestId, Instant completedAt) {
        jdbc.update("""
                with confirmed as (
                  update identity_access.ia_remote_logout_outbox
                  set state='confirmed', last_error_code=null, updated_at=?
                  where request_id=? and state<>'confirmed'
                  returning command_type, idempotency_key, registration_id
                )
                update identity_access.ia_idempotency_result result
                set result_remote_pending=false,
                    result_next_action=case
                      when confirmed.command_type='ACCOUNT_SWITCH'
                      then '/oauth2/authorization/' || confirmed.registration_id
                      else result.result_next_action
                    end
                from confirmed
                where result.command_type=confirmed.command_type
                  and result.idempotency_key=confirmed.idempotency_key
                """, timestamp(completedAt), java.util.UUID.fromString(requestId));
    }

    @Override
    public void markRetry(String requestId, String safeErrorCode, Instant nextAttemptAt) {
        if (!safeErrorCode.matches("[A-Z0-9_]{3,128}")) {
            throw new IllegalArgumentException("IDENTITY_REMOTE_ERROR_CODE_INVALID");
        }
        jdbc.update("""
                update identity_access.ia_remote_logout_outbox
                set state='retrying', last_error_code=?, next_attempt_at=?, updated_at=?
                where request_id=?
                """, safeErrorCode, timestamp(nextAttemptAt), timestamp(Instant.now()),
                java.util.UUID.fromString(requestId));
    }

    @Override
    public void save(EncryptedAuthorizedClient client) {
        var access = client.accessToken();
        var refresh = client.refreshToken();
        if (!access.keyRef().equals(refresh.keyRef()) || !access.keyVersion().equals(refresh.keyVersion())) {
            throw new IdentityAccessException(
                    "IDENTITY_KEY_METADATA_MISMATCH", "token encryption metadata is inconsistent");
        }
        jdbc.update("""
                insert into identity_access.ia_refresh_secret (
                  session_id, registration_id, access_ciphertext, access_wrapped_dek, access_nonce,
                  refresh_ciphertext, refresh_wrapped_dek, refresh_nonce, key_ref, key_version,
                  access_expires_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (session_id, registration_id) do update set
                  access_ciphertext=excluded.access_ciphertext,
                  access_wrapped_dek=excluded.access_wrapped_dek,
                  access_nonce=excluded.access_nonce,
                  refresh_ciphertext=excluded.refresh_ciphertext,
                  refresh_wrapped_dek=excluded.refresh_wrapped_dek,
                  refresh_nonce=excluded.refresh_nonce,
                  key_ref=excluded.key_ref,
                  key_version=excluded.key_version,
                  access_expires_at=excluded.access_expires_at,
                  updated_at=excluded.updated_at
                """, client.sessionId(), client.registrationId(), access.ciphertext(),
                access.wrappedDataKey(), access.nonce(), refresh.ciphertext(),
                refresh.wrappedDataKey(), refresh.nonce(), access.keyRef(), access.keyVersion(),
                timestamp(client.accessExpiresAt()), timestamp(Instant.now()));
    }

    @Override
    public Optional<EncryptedAuthorizedClient> find(String sessionId, String registrationId) {
        return jdbc.query("""
                select * from identity_access.ia_refresh_secret
                where session_id=? and registration_id=?
                """, (rs, row) -> new EncryptedAuthorizedClient(
                rs.getString("session_id"),
                rs.getString("registration_id"),
                new cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret(
                        rs.getBytes("access_ciphertext"), rs.getBytes("access_wrapped_dek"),
                        rs.getString("key_ref"), rs.getString("key_version"),
                        rs.getBytes("access_nonce")),
                new cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret(
                        rs.getBytes("refresh_ciphertext"), rs.getBytes("refresh_wrapped_dek"),
                        rs.getString("key_ref"), rs.getString("key_version"),
                        rs.getBytes("refresh_nonce")),
                instant(rs, "access_expires_at")), sessionId, registrationId).stream().findFirst();
    }

    private boolean exists(String sessionId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from identity_access.ia_identity_session where session_id=?",
                Integer.class, sessionId);
        return count != null && count > 0;
    }

    private static IdentitySession mapSession(ResultSet rs, int row) throws SQLException {
        String encoded = rs.getString("used_refresh_digests");
        Set<String> used = encoded == null || encoded.isBlank()
                ? Set.of()
                : Arrays.stream(encoded.split(",")).collect(Collectors.toUnmodifiableSet());
        return IdentitySession.restore(
                rs.getString("session_id"), rs.getString("session_pseudonym"),
                rs.getString("actor_pseudonym"),
                rs.getString("browser_binding_hash"), rs.getString("origin"),
                instant(rs, "created_at"), instant(rs, "last_activity_at"),
                instant(rs, "access_expires_at"), instant(rs, "idle_expires_at"),
                instant(rs, "absolute_expires_at"), instant(rs, "warning_at"),
                rs.getLong("session_version"), rs.getString("refresh_family_id"),
                rs.getString("current_refresh_digest"), used,
                SessionStatus.valueOf(rs.getString("status")));
    }

    private static StoredSessionCommand mapStoredSessionCommand(ResultSet rs, int row)
            throws SQLException {
        SessionCommandType type = SessionCommandType.valueOf(rs.getString("command_type"));
        return new StoredSessionCommand(
                rs.getString("session_id"),
                type,
                rs.getString("idempotency_key"),
                rs.getString("request_digest"),
                new SessionCommandResult(
                        rs.getString("result_session_pseudonym"),
                        type,
                        rs.getLong("result_session_version"),
                        instant(rs, "result_completed_at"),
                        rs.getString("result_next_action"),
                        rs.getBoolean("result_remote_pending")));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Timestamp timestampOrNull(Instant value) {
        return value == null ? null : timestamp(value);
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        return rs.getTimestamp(field).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
