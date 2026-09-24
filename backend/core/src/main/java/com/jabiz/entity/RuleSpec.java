package com.jabiz.entity;

import java.util.Map;

/** Serializable description of a rule (code, kind and parameters), exported to clients. */
public record RuleSpec(String code, String kind, Map<String, Object> params) {}
