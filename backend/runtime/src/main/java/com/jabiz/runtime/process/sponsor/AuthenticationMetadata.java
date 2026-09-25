package com.jabiz.runtime.process.sponsor;

/**
 * @param inputKeyUsername    context key holding the submitted username or e-mail
 * @param inputKeyPassword    context key holding the submitted password
 * @param userEntityType      entity type of the user to load
 * @param outputUserEntityKey context key under which the authenticated user is stored
 */
public record AuthenticationMetadata(
    String inputKeyUsername,
    String inputKeyPassword,
    String userEntityType,
    String outputUserEntityKey
) {}
