package com.jabiz.resource;

import java.util.regex.Pattern;

/**
 * Uniform identifier of a resource: {@code urn:<namespace>:<kind>:<type>:<id>}.
 *
 * <ul>
 *   <li>namespace, kind: lower-case letters, digits and hyphens</li>
 *   <li>type, id: letters, digits, dot, underscore and hyphen (mixed case allowed, so
 *       registered names such as "WaybillTracking" or "SPONSOR_SIGN_IN" are valid types)</li>
 * </ul>
 *
 * Examples: {@code urn:jabiz:entity:WaybillTracking:WB-1001},
 * {@code urn:jabiz:process:SPONSOR_SIGN_IN:1}.
 */
public record ResourceId(String namespace, String kind, String type, String id) {

    private static final Pattern LOWER_SEGMENT = Pattern.compile("[a-z0-9-]+");
    private static final Pattern MIXED_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    public ResourceId {
        requireSegment(namespace, LOWER_SEGMENT, "namespace");
        requireSegment(kind, LOWER_SEGMENT, "kind");
        requireSegment(type, MIXED_SEGMENT, "type");
        requireSegment(id, MIXED_SEGMENT, "id");
    }

    public static ResourceId parse(String urn) {
        if (urn == null) {
            throw new InvalidResourceIdException("Resource identifier must not be null");
        }
        String[] parts = urn.split(":", -1);
        if (parts.length != 5 || !"urn".equals(parts[0])) {
            throw new InvalidResourceIdException(
                "Malformed resource identifier (expected urn:<namespace>:<kind>:<type>:<id>): " + urn);
        }
        return new ResourceId(parts[1], parts[2], parts[3], parts[4]);
    }

    @Override
    public String toString() {
        return "urn:%s:%s:%s:%s".formatted(namespace, kind, type, id);
    }

    private static void requireSegment(String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidResourceIdException("Invalid resource identifier segment '" + what + "': " + value);
        }
    }
}
