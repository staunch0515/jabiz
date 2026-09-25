package com.jabiz.process.entity;

/** Result of a successful delete. */
public record DeleteEntityOutput(String entityType, Object id) {}
