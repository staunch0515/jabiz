package com.jabiz.runtime.security;

import com.jabiz.runtime.EntityNotFoundException;

/**
 * 404 {@code SESSION_NOT_FOUND}: no live session of the user with this id (docs/design/10-security.md section 15.9).
 * Said alike whether the session never existed, has ended, is somebody else's or of another entry, so that nobody
 * learns about sessions that are not theirs.
 */
public class SessionNotFoundException extends EntityNotFoundException {

    public SessionNotFoundException() {
        super("No such session");
    }
}
