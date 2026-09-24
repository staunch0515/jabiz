package com.jabiz.process.sponsor;

/**
 * @param targetUserEntityKey   context key of the user the record is created for
 * @param loginRecordEntityType entity type of the login audit record
 * @param usedRoleSlug          role under which the login happens
 * @param commitType            kind of change written for the record (for example CREATE)
 */
public record LoginRecordMetadata(
    String targetUserEntityKey,
    String loginRecordEntityType,
    String usedRoleSlug,
    String commitType
) {}
