package com.jabiz.entity;

import java.util.function.LongPredicate;

public record SpatialGuardRule(String targetStatus, String locationField, LongPredicate guard) {}
