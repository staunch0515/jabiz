package com.jabiz.testkinds;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.QueryOperator;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Custom kind used by core tests: a 64-bit cell number that supports equality only. */
public final class TestCellKind implements CustomKindSupport {

    public static final String ID = "test.cell";

    static {
        CustomKinds.register(new TestCellKind());
    }

    /** The kind with the given level; registers the support on first use. */
    public static SemanticKind.Custom of(int level) {
        return new SemanticKind.Custom(ID, Map.of("level", level));
    }

    @Override
    public String kindId() {
        return ID;
    }

    @Override
    public Object coerce(Map<String, Object> params, Object raw, boolean forInput) {
        return FieldValueCoercer.toLong(raw);
    }

    @Override
    public Class<?> javaType(Map<String, Object> params) {
        return Long.class;
    }

    @Override
    public Set<QueryOperator> allowedOperators(Map<String, Object> params) {
        return EnumSet.of(QueryOperator.EQ, QueryOperator.NE, QueryOperator.IN);
    }

    @Override
    public Map<String, Object> export(Map<String, Object> params) {
        return Map.of("level", params.get("level"));
    }
}
