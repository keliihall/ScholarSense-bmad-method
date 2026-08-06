package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Objects;

/** Server-only session resolution; the actor pseudonym is never part of the browser projection. */
public record InternalSessionProjection(
        CurrentSessionProjection session,
        String actorPseudonym) {
    public InternalSessionProjection {
        Objects.requireNonNull(session, "session");
        if (actorPseudonym == null || actorPseudonym.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_ACTOR_PSEUDONYM_INVALID");
        }
    }
}
