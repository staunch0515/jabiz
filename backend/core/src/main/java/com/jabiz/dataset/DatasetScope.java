package com.jabiz.dataset;

import com.jabiz.context.DataPeriod;
import com.jabiz.context.RequestContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Range of a dataset (docs/design/03-dataset.md section 2.2): for each listed field, the value every row of the
 * target entity must have. Values are either fixed or taken from the {@link RequestContext}. The scope applies
 * to reads (rows outside are invisible) and to writes (inserts are filled in, changes may not leave it).
 *
 * <p>A value taken from the context that is missing rejects the request ({@link ScopeUnavailableException}):
 * the scope never silently degrades to "no filter". The one exception is {@link WithinDataPeriod}, where "no period"
 * means "not limited in time" by design (decision D28 item 8). Its resolved value is a {@link PeriodCondition}, a
 * range rather than a value.
 */
public final class DatasetScope {

    /** Condition of one field. */
    public sealed interface Entry permits Fixed, FromContext, WithinDataPeriod {
        String field();
    }

    public record Fixed(String field, Object value) implements Entry {
        public Fixed {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(value, "scope value of " + field + " must not be null");
        }
    }

    /**
     * @param source name of the context attribute, shown when it is missing
     */
    public record FromContext(String field, String source, Function<RequestContext, Object> value) implements Entry {
        public FromContext {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * Rows whose time lies within the actor's {@linkplain RequestContext#dataPeriod() data period}
     * (docs/design/10-security.md section 13.2): the time is in {@code field} itself (a temporal field), or, when
     * {@code referencedField} is given, {@code field} refers to another entity whose immutable temporal field
     * {@code referencedField} holds it (a ledger entry and its transaction's booking time). Unlike {@link FromContext},
     * an actor without a period is not limited, which is why the scope must be declared explicitly.
     */
    public record WithinDataPeriod(String field, String referencedField) implements Entry {
        public WithinDataPeriod {
            Objects.requireNonNull(field, "field must not be null");
        }
    }

    /**
     * Resolved value of a {@link WithinDataPeriod} entry for an actor with a period: the rows must have their time
     * within {@code period}, taken from the referenced entity's {@code referencedField} when that is not null.
     */
    public record PeriodCondition(DataPeriod period, String referencedField) {
        public PeriodCondition {
            Objects.requireNonNull(period, "period must not be null");
        }
    }

    public static final DatasetScope NONE = new DatasetScope(List.of());

    private final List<Entry> entries;

    private DatasetScope(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True if some value comes from the request context, so the scope differs between callers. */
    public boolean isDynamic() {
        return entries.stream().anyMatch(entry -> !(entry instanceof Fixed));
    }

    /**
     * Field values of this scope for the given request, in declaration order.
     *
     * @throws ScopeUnavailableException if a value taken from the context is missing
     */
    public Map<String, Object> resolve(RequestContext request) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Entry entry : entries) {
            switch (entry) {
                case Fixed fixed -> values.put(fixed.field(), fixed.value());
                case FromContext dynamic -> {
                    Object value = request == null ? null : dynamic.value().apply(request);
                    if (value == null || (value instanceof String s && s.isBlank())) {
                        throw new ScopeUnavailableException(dynamic.field(), dynamic.source());
                    }
                    values.put(dynamic.field(), value);
                }
                case WithinDataPeriod within -> {
                    DataPeriod period = request == null ? null : request.dataPeriod();
                    if (period != null) {
                        values.put(within.field(), new PeriodCondition(period, within.referencedField()));
                    }
                }
            }
        }
        return Collections.unmodifiableMap(values);
    }

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        /** Every row has {@code value} in {@code field}. */
        public Builder fixed(String field, Object value) {
            return add(new Fixed(field, value));
        }

        /** Every row has, in {@code field}, the value the request context yields; the field names the source. */
        public Builder fromContext(String field, Function<RequestContext, Object> value) {
            return fromContext(field, field, value);
        }

        /** As {@link #fromContext(String, Function)}, naming the context attribute for error messages. */
        public Builder fromContext(String field, String source, Function<RequestContext, Object> value) {
            return add(new FromContext(field, source, value));
        }

        /**
         * Rows whose temporal {@code field} lies within the actor's data period; actors without one see every row
         * (docs/design/10-security.md section 13.2).
         */
        public Builder withinDataPeriod(String field) {
            return add(new WithinDataPeriod(field, null));
        }

        /**
         * Rows whose reference {@code field} points to an instance whose immutable temporal field
         * {@code referencedField} lies within the actor's data period.
         */
        public Builder withinDataPeriod(String field, String referencedField) {
            return add(new WithinDataPeriod(field, Objects.requireNonNull(referencedField,
                "referencedField must not be null")));
        }

        private Builder add(Entry entry) {
            if (entries.stream().anyMatch(e -> e.field().equals(entry.field()))) {
                throw new IllegalArgumentException("scope field " + entry.field() + " is declared twice");
            }
            entries.add(entry);
            return this;
        }

        DatasetScope build() {
            return entries.isEmpty() ? NONE : new DatasetScope(entries);
        }
    }
}
