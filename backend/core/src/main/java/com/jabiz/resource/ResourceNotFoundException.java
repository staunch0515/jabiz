package com.jabiz.resource;

/**
 * The addressed resource does not exist. This is a normal business outcome and is
 * translated to a 404 response at the web boundary.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
