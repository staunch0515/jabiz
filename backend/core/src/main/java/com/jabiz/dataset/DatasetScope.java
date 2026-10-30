package com.jabiz.dataset;

import com.jabiz.context.DataPeriod;
import com.jabiz.context.EntryScope;
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
 * range rather than a value. Neither do the entry scopes of decision D64 ({@link WithinEntryScope},
 * {@link WithinEntryScopeTag}): they limit the sessions of a scoped entry only, others see every row.
 */
public final class DatasetScope {

    /** Condition of one field. */
    public sealed interface Entry permits Fixed, FromContext, WithinDataPeriod, WithinEntryScope, WithinEntryScopeTag {
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
     * Rows of the session's entry scope {@code scope} (decision D64; docs/design/10-security.md section 13.4): those
     * whose {@code field} is the session's value, or, with {@code via}, whose {@code field} maps to it through another
     * entity. Sessions without that scope are not limited.
     */
    public record WithinEntryScope(String scope, String field, EntryScopeVia via) implements Entry {
        public WithinEntryScope {
            Objects.requireNonNull(scope, "scope must not be null");
            if (!EntryScope.NAME.matcher(scope).matches()) {
                throw new IllegalArgumentException("Invalid scope name: " + scope);
            }
            Objects.requireNonNull(field, "field must not be null");
        }
    }

    /**
     * Rows that say themselves which entry scope they belong to (decision D64 item 3; the platform's approvals and
     * tasks): a session with an entry scope sees the rows whose {@code nameField} is its scope's name and
     * {@code field} its value, and no row without them, except, with {@code ownerField}, the rows whose owner it is
     * (a task given to its user personally). Sessions without a scope are not limited.
     */
    public record WithinEntryScopeTag(String nameField, String field, String ownerField) implements Entry {
        public WithinEntryScopeTag {
            Objects.requireNonNull(nameField, "nameField must not be null");
            Objects.requireNonNull(field, "field must not be null");
        }
    }

    /**
     * Resolved value of a {@link WithinEntryScope} entry through another entity: the dataset's field must be a key of
     * {@code via}'s entity whose scope field is {@code value}.
     */
    public record EntryScopeCondition(String value, EntryScopeVia via) {
        public EntryScopeCondition {
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(via, "via must not be null");
        }
    }

    /**
     * Resolved value of a {@link WithinEntryScopeTag} entry: the row's name field is {@code scope}'s name and its field
     * the value, or, with {@code ownerField}, the row's owner is {@code actorId}.
     */
    public record EntryScopeTagCondition(String nameField, EntryScope scope, String ownerField, String actorId) {
        public EntryScopeTagCondition {
            Objects.requireNonNull(nameField, "nameField must not be null");
            Objects.requireNonNull(scope, "scope must not be null");
            if (ownerField != null) {
                Objects.requireNonNull(actorId, "actorId must not be null");
            }
        }

        public EntryScopeTagCondition(String nameField, EntryScope scope) {
            this(nameField, scope, null, null);
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
                case WithinEntryScope within -> {
                    String value = request == null ? null : request.entryScopeValue(within.scope());
                    if (value != null) {
                        values.put(within.field(), within.via() == null ? value
                            : new EntryScopeCondition(value, within.via()));
                    }
                }
                case WithinEntryScopeTag tag -> {
                    EntryScope scope = request == null ? null : request.entryScope();
                    if (scope != null) {
                        values.put(tag.field(), new EntryScopeTagCondition(tag.nameField(), scope, tag.ownerField(),
                            request.actorId()));
                    }
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

        /**
         * Rows whose {@code field} is the session's value of the entry scope {@code scope} (decision D64); sessions
         * without it see every row.
         */
        public Builder withinEntryScope(String scope, String field) {
            return add(new WithinEntryScope(scope, field, null));
        }

        /**
         * Rows whose {@code field} maps to the session's value of the entry scope {@code scope} through {@code via}'s
         * entity (decision D64); sessions without it see every row. Scoped sessions cannot write through the dataset.
         */
        public Builder withinEntryScope(String scope, String field, EntryScopeVia via) {
            return add(new WithinEntryScope(scope, field, Objects.requireNonNull(via, "via must not be null")));
        }

        /**
         * Rows that name their entry scope in {@code nameField} and its value in {@code field} (decision D64 item 3):
         * a session with any entry scope sees those of its own only; sessions without one see every row. Scoped
         * sessions cannot write through the dataset.
         */
        public Builder withinEntryScopeTag(String nameField, String field) {
            return add(new WithinEntryScopeTag(nameField, field, null));
        }

        /**
         * As {@link #withinEntryScopeTag(String, String)}, the rows whose {@code ownerField} is the session's user
         * visible to it whatever their scope (a task given to that user personally).
         */
        public Builder withinEntryScopeTag(String nameField, String field, String ownerField) {
            return add(new WithinEntryScopeTag(nameField, field, Objects.requireNonNull(ownerField,
                "ownerField must not be null")));
        }

        private Builder add(Entry entry) {
            for (String field : fields(entry)) {
                if (entries.stream().anyMatch(e -> fields(e).contains(field))) {
                    throw new IllegalArgumentException("scope field " + field + " is declared twice");
                }
            }
            entries.add(entry);
            return this;
        }

        /** The fields an entry limits: a tag limits its name field too. */
        private static List<String> fields(Entry entry) {
            return entry instanceof WithinEntryScopeTag tag ? List.of(tag.nameField(), tag.field())
                : List.of(entry.field());
        }

        DatasetScope build() {
            return entries.isEmpty() ? NONE : new DatasetScope(entries);
        }
    }
}
