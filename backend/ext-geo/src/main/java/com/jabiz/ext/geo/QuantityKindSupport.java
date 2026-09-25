package com.jabiz.ext.geo;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.query.QueryOperator;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** {@link GeoKinds#QUANTITY}: a decimal amount of a physical dimension; ordered, not text-searchable. */
public final class QuantityKindSupport implements CustomKindSupport {

    @Override
    public String kindId() {
        return GeoKinds.QUANTITY;
    }

    @Override
    public Object coerce(Map<String, Object> params, Object raw, boolean forInput) {
        return FieldValueCoercer.toDecimal(raw);
    }

    @Override
    public Class<?> javaType(Map<String, Object> params) {
        return BigDecimal.class;
    }

    @Override
    public Set<QueryOperator> allowedOperators(Map<String, Object> params) {
        return EnumSet.complementOf(EnumSet.of(QueryOperator.LIKE));
    }

    @Override
    public Map<String, Object> export(Map<String, Object> params) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("dimension", params.get("dimension"));
        json.put("unit", params.get("unit"));
        return json;
    }
}
