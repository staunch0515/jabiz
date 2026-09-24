package com.jabiz.resource;

/** A resource identifier (URN) is malformed or one of its segments is not acceptable. */
public class InvalidResourceIdException extends IllegalArgumentException {
    public InvalidResourceIdException(String message) {
        super(message);
    }
}
