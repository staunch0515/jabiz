package com.jabiz.process;

/** Metadata of a step that needs no configuration. */
public record NoMetadata() {

    public static final NoMetadata INSTANCE = new NoMetadata();
}
