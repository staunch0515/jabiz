package com.jabiz.runtime;

/**
 * Signing in or refreshing a session failed; answered with 401 and the given code. The message is for logs; callers
 * are told as little as possible (docs/design/10-security.md section 4).
 */
public class AuthenticationFailedException extends RuntimeException {

    private final String code;

    public AuthenticationFailedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
