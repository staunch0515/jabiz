package com.jabiz.entity;

public enum TemporalRole {
    /** Business event time; subject to causality constraints. */
    EVENT_TIME,
    /** System-issued audit time; stamped on insert and never accepted from callers. */
    SYSTEM_RECORDED,
    /** Bitemporal: start of business validity. */
    VALID_FROM,
    /** Bitemporal: end of business validity. */
    VALID_TO
}
