package com.jabiz.ext.geo;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.query.QueryOperator;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link GeoKinds#H3}: an H3 cell index as a 64-bit integer (decimal or {@code 0x}-prefixed hexadecimal text on
 * input). Cell indexes have no meaningful order, so only equality comparisons are allowed.
 */
public final class H3KindSupport implements CustomKindSupport {

    @Override
    public String kindId() {
        return GeoKinds.H3;
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
        return EnumSet.of(QueryOperator.EQ, QueryOperator.NE, QueryOperator.IN,
            QueryOperator.IS_NULL, QueryOperator.IS_NOT_NULL);
    }

    @Override
    public Map<String, Object> export(Map<String, Object> params) {
        return Map.of("resolution", params.get("resolution"));
    }
}
