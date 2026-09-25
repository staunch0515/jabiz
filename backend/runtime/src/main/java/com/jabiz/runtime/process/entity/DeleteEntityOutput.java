package com.jabiz.runtime.process.entity;

/** Result of a successful delete. */
public record DeleteEntityOutput(String entityType, Object id) {}
