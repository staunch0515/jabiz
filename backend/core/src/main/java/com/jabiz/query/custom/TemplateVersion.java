package com.jabiz.query.custom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * The version of a SQL template (docs/design/19-reports.md section 2.3): SHA-256 (hex) of the file's text, or of a
 * canonical description for templates declared in Java. Any change - even to a description - is a new version;
 * line endings and a byte order mark are not.
 */
public final class TemplateVersion {

    private TemplateVersion() {}

    /** Version of a template file's whole text. */
    public static String ofText(String text) {
        String normalized = text.startsWith("﻿") ? text.substring(1) : text;
        return sha256(normalized.replace("\r\n", "\n").replace('\r', '\n'));
    }

    /** Version of a template declared with the Java DSL, from everything that defines it. */
    public static String of(AdvancedQueryDefinition query) {
        StringBuilder text = new StringBuilder()
            .append("id=").append(query.queryId()).append('\n')
            .append("description=").append(query.description()).append('\n')
            .append("entities=").append(query.participatingEntities()).append('\n')
            .append("datasets=").append(new TreeMap<>(query.datasets())).append('\n')
            .append("params=").append(query.parameters()).append('\n')
            .append("results=").append(query.resultFields()).append('\n')
            .append("list=").append(query.list()).append('\n')
            .append("permissions=").append(query.permissions()).append('\n')
            .append("timeout=").append(query.timeoutOverride()).append('\n')
            .append("public=").append(query.publicAccess()).append('\n')
            .append("cacheSeconds=").append(query.cacheSeconds()).append('\n')
            .append("timeSlice=").append(query.timeSlice()).append('\n')
            .append("report=").append(query.report()).append('\n')
            .append("sql=").append(query.sqlTemplate());
        return ofText(text.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
