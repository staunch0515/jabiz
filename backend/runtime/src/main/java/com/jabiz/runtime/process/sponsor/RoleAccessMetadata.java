package com.jabiz.process.sponsor;

/**
 * @param targetUserEntityKey  context key of the user whose role is checked
 * @param requiredRoleSlug     role the user must hold
 * @param roleRelationshipType relationship type linking a user to a role
 */
public record RoleAccessMetadata(
    String targetUserEntityKey,
    String requiredRoleSlug,
    String roleRelationshipType
) {}
