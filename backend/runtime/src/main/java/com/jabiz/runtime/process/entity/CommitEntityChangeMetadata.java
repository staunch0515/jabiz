package com.jabiz.process.entity;

import com.jabiz.runtime.EntityAction;

/** @param action the kind of change the commit step writes */
public record CommitEntityChangeMetadata(EntityAction action) {}
