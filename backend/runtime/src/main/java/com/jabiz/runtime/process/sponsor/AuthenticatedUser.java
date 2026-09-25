package com.jabiz.process.sponsor;

/** The user identity established by the authentication step, reduced to what later steps need. */
public record AuthenticatedUser(String userId, int failedLoginAttempts) {}
