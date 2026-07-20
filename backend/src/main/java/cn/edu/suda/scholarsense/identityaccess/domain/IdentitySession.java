package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Immutable session aggregate. Token material is represented only by one-way digests. */
public final class IdentitySession {
    public static final Duration ACCESS_WINDOW = Duration.ofMinutes(5);
    public static final Duration IDLE_WINDOW = Duration.ofMinutes(15);
    public static final Duration ABSOLUTE_WINDOW = Duration.ofHours(8);
    public static final Duration WARNING_WINDOW = Duration.ofMinutes(5);

    private final String sessionId;
    private final String sessionPseudonym;
    private final String actorPseudonym;
    private final String browserBindingHash;
    private final String origin;
    private final Instant createdAt;
    private final Instant lastActivityAt;
    private final Instant accessExpiresAt;
    private final Instant idleExpiresAt;
    private final Instant absoluteExpiresAt;
    private final Instant warningAt;
    private final long sessionVersion;
    private final String refreshFamilyId;
    private final String currentRefreshDigest;
    private final Set<String> usedRefreshDigests;
    private final SessionStatus status;

    private IdentitySession(
            String sessionId,
            String sessionPseudonym,
            String actorPseudonym,
            String browserBindingHash,
            String origin,
            Instant createdAt,
            Instant lastActivityAt,
            Instant accessExpiresAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            Instant warningAt,
            long sessionVersion,
            String refreshFamilyId,
            String currentRefreshDigest,
            Set<String> usedRefreshDigests,
            SessionStatus status) {
        this.sessionId = required(sessionId, "IDENTITY_SESSION_ID_INVALID");
        this.sessionPseudonym = requiredSessionPseudonym(sessionPseudonym);
        this.actorPseudonym = required(actorPseudonym, "IDENTITY_ACTOR_PSEUDONYM_INVALID");
        this.browserBindingHash = required(browserBindingHash, "IDENTITY_BROWSER_BINDING_INVALID");
        this.origin = required(origin, "IDENTITY_ORIGIN_INVALID");
        this.createdAt = Objects.requireNonNull(createdAt);
        this.lastActivityAt = Objects.requireNonNull(lastActivityAt);
        this.accessExpiresAt = Objects.requireNonNull(accessExpiresAt);
        this.idleExpiresAt = Objects.requireNonNull(idleExpiresAt);
        this.absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt);
        this.warningAt = Objects.requireNonNull(warningAt);
        if (sessionVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_SESSION_VERSION_INVALID");
        }
        this.sessionVersion = sessionVersion;
        this.refreshFamilyId = required(refreshFamilyId, "IDENTITY_REFRESH_FAMILY_INVALID");
        this.currentRefreshDigest = required(currentRefreshDigest, "IDENTITY_REFRESH_DIGEST_INVALID");
        this.usedRefreshDigests = Set.copyOf(usedRefreshDigests);
        this.status = Objects.requireNonNull(status);
    }

    public static IdentitySession authenticate(
            String sessionId,
            String sessionPseudonym,
            String actorPseudonym,
            String browserBindingHash,
            String origin,
            String refreshFamilyId,
            String refreshDigest,
            Instant now) {
        Instant idleExpiresAt = now.plus(IDLE_WINDOW);
        Instant absoluteExpiresAt = now.plus(ABSOLUTE_WINDOW);
        return new IdentitySession(
                sessionId, sessionPseudonym, actorPseudonym, browserBindingHash, origin, now, now,
                now.plus(ACCESS_WINDOW), idleExpiresAt, absoluteExpiresAt,
                idleExpiresAt.minus(WARNING_WINDOW), 1, refreshFamilyId, refreshDigest,
                Set.of(), SessionStatus.ACTIVE);
    }

    public static IdentitySession restore(
            String sessionId,
            String sessionPseudonym,
            String actorPseudonym,
            String browserBindingHash,
            String origin,
            Instant createdAt,
            Instant lastActivityAt,
            Instant accessExpiresAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            Instant warningAt,
            long sessionVersion,
            String refreshFamilyId,
            String currentRefreshDigest,
            Set<String> usedRefreshDigests,
            SessionStatus status) {
        return new IdentitySession(
                sessionId, sessionPseudonym, actorPseudonym, browserBindingHash, origin,
                createdAt, lastActivityAt,
                accessExpiresAt, idleExpiresAt, absoluteExpiresAt, warningAt, sessionVersion,
                refreshFamilyId, currentRefreshDigest, usedRefreshDigests, status);
    }

    public RefreshRotation rotateRefresh(
            String presentedDigest, String replacementDigest, long expectedVersion, Instant now) {
        requireVersion(expectedVersion);
        requireActive(now);
        Set<String> used = new HashSet<>(usedRefreshDigests);
        if (!currentRefreshDigest.equals(presentedDigest) || used.contains(presentedDigest)) {
            used.add(presentedDigest);
            return new RefreshRotation(copy(
                    now, accessExpiresAt, idleExpiresAt, warningAt, sessionVersion + 1,
                    currentRefreshDigest, used, SessionStatus.REFRESH_FAMILY_REVOKED), true);
        }
        used.add(currentRefreshDigest);
        Instant nextIdle = earliest(now.plus(IDLE_WINDOW), absoluteExpiresAt);
        IdentitySession rotated = copy(
                now, now.plus(ACCESS_WINDOW), nextIdle, nextIdle.minus(WARNING_WINDOW),
                sessionVersion + 1, required(replacementDigest, "IDENTITY_REFRESH_DIGEST_INVALID"),
                used, SessionStatus.ACTIVE);
        return new RefreshRotation(rotated, false);
    }

    public boolean acceptsRefresh(String presentedDigest, long expectedVersion, Instant now) {
        requireVersion(expectedVersion);
        requireActive(now);
        return currentRefreshDigest.equals(presentedDigest) && !usedRefreshDigests.contains(presentedDigest);
    }

    public IdentitySession revoke(long expectedVersion, Instant now) {
        requireVersion(expectedVersion);
        if (status != SessionStatus.ACTIVE) {
            throw new IdentityAccessException("IDENTITY_SESSION_EXPIRED", "session is no longer active");
        }
        return copy(
                now, accessExpiresAt, idleExpiresAt, warningAt, sessionVersion + 1,
                currentRefreshDigest, usedRefreshDigests, SessionStatus.REVOKED);
    }

    public IdentitySession requireReauthentication(Instant now) {
        if (status == SessionStatus.REAUTHENTICATION_REQUIRED) {
            return this;
        }
        return copy(
                now, accessExpiresAt, idleExpiresAt, warningAt, sessionVersion + 1,
                currentRefreshDigest, usedRefreshDigests, SessionStatus.REAUTHENTICATION_REQUIRED);
    }

    public boolean activeAt(Instant now) {
        return status == SessionStatus.ACTIVE && now.isBefore(earliest(idleExpiresAt, absoluteExpiresAt));
    }

    private void requireActive(Instant now) {
        if (!activeAt(now)) {
            throw new IdentityAccessException("IDENTITY_SESSION_EXPIRED", "session is no longer active");
        }
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != sessionVersion) {
            throw new IdentityAccessException(
                    "IDENTITY_SESSION_VERSION_CONFLICT", "session changed; refresh before retrying");
        }
    }

    private IdentitySession copy(
            Instant activityAt,
            Instant nextAccessExpiresAt,
            Instant nextIdleExpiresAt,
            Instant nextWarningAt,
            long nextVersion,
            String nextRefreshDigest,
            Set<String> nextUsedDigests,
            SessionStatus nextStatus) {
        return new IdentitySession(
                sessionId, sessionPseudonym, actorPseudonym, browserBindingHash, origin,
                createdAt, activityAt,
                nextAccessExpiresAt, nextIdleExpiresAt, absoluteExpiresAt, nextWarningAt,
                nextVersion, refreshFamilyId, nextRefreshDigest, nextUsedDigests, nextStatus);
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String required(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(code);
        }
        return value;
    }

    private static String requiredSessionPseudonym(String value) {
        if (value == null || !value.matches("sp_[A-Za-z0-9_-]{20,86}")) {
            throw new IllegalArgumentException("IDENTITY_SESSION_PSEUDONYM_INVALID");
        }
        return value;
    }

    public String sessionId() { return sessionId; }
    public String sessionPseudonym() { return sessionPseudonym; }
    public String actorPseudonym() { return actorPseudonym; }
    public String browserBindingHash() { return browserBindingHash; }
    public String origin() { return origin; }
    public Instant createdAt() { return createdAt; }
    public Instant lastActivityAt() { return lastActivityAt; }
    public Instant accessExpiresAt() { return accessExpiresAt; }
    public Instant idleExpiresAt() { return idleExpiresAt; }
    public Instant absoluteExpiresAt() { return absoluteExpiresAt; }
    public Instant warningAt() { return warningAt; }
    public long sessionVersion() { return sessionVersion; }
    public String refreshFamilyId() { return refreshFamilyId; }
    public String currentRefreshDigest() { return currentRefreshDigest; }
    public Set<String> usedRefreshDigests() { return usedRefreshDigests; }
    public SessionStatus status() { return status; }
}
