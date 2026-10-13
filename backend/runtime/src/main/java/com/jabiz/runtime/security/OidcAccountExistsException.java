package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * An identity provider vouched for an address that belongs to an account already: no account is opened and nothing is
 * linked (decision D39 item 2); answered with 403 {@code OIDC_ACCOUNT_EXISTS}. The provider has just proven to the
 * caller's browser that the address is theirs, so telling them apart leaks nothing they did not know (as decision D36
 * implementation note 2 reasons for refusals after a right password).
 */
public class OidcAccountExistsException extends PermissionDeniedException {

    public OidcAccountExistsException(String message) {
        super("-", message);
    }
}
