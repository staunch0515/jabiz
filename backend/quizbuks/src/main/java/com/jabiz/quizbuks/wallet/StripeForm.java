package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A request body or query of Stripe's API: {@code application/x-www-form-urlencoded}, nested values written with
 * brackets ({@code line_items[0][price_data][unit_amount]=5000}, {@code expand[]=latest_charge}) in the order they
 * were added. Keys and values are escaped by the form rules (UTF-8, a space as {@code +}, the brackets too), so no
 * value can add a field of its own. Immutable.
 */
public final class StripeForm {

    /** One field. */
    public record Field(String key, String value) {
        public Field {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            if (key.isEmpty()) {
                throw new IllegalArgumentException("A form field needs a key");
            }
        }
    }

    private static final StripeForm EMPTY = new StripeForm(List.of());

    private final List<Field> fields;

    private StripeForm(List<Field> fields) {
        this.fields = List.copyOf(fields);
    }

    public static StripeForm of() {
        return EMPTY;
    }

    /** This form with one more field; a null value adds nothing (Stripe's default applies). */
    public StripeForm with(String key, Object value) {
        if (value == null) {
            return this;
        }
        List<Field> more = new ArrayList<>(fields);
        more.add(new Field(key, text(value)));
        return new StripeForm(more);
    }

    public List<Field> fields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** The value of the first field of {@code key}, or null. */
    public String get(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).map(Field::value).findFirst().orElse(null);
    }

    /** {@code key=value&…}, escaped. */
    public String encode() {
        StringJoiner joined = new StringJoiner("&");
        for (Field field : fields) {
            joined.add(escape(field.key()) + "=" + escape(field.value()));
        }
        return joined.toString();
    }

    /** The keys only: values may be personal. */
    @Override
    public String toString() {
        return "StripeForm" + fields.stream().map(Field::key).toList();
    }

    private static String text(Object value) {
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    static String escape(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
