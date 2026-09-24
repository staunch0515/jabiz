package com.jabiz.runtime;

/** The addressed entity instance does not exist within the dataset scope. */
public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }
}
