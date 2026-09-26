package com.jabiz.runtime.context;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Objects;

/** An authenticated request: its {@link Actor}, established from an access token (or, in development, headers). */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    private final Actor actor;

    public ActorAuthentication(Actor actor) {
        super(Objects.requireNonNull(actor, "actor must not be null").permissions().stream()
            .map(SimpleGrantedAuthority::new).toList());
        this.actor = actor;
        setAuthenticated(true);
    }

    public Actor actor() {
        return actor;
    }

    /** Access tokens are not kept around once verified. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return actor.actorId();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ActorAuthentication that && actor.equals(that.actor);
    }

    @Override
    public int hashCode() {
        return actor.hashCode();
    }
}
