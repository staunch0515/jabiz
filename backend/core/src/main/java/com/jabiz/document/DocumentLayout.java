package com.jabiz.document;

import com.jabiz.approval.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The layout of a business document such as an invoice or an order confirmation (docs/design/22-documents.md,
 * decision D30): blocks laid out top to bottom, each showing columns of an ordinary SQL template. The platform reads
 * the templates at one point in time, archives what they returned, writes a PDF and keeps its bytes
 * ({@code DOCUMENT_ISSUE}). The layout is declared as a bean; it has no data of its own.
 *
 * <pre>{@code
 * DocumentLayout.define("commerce.order_confirmation", d -> d
 *     .permissions("commerce.order.read")
 *     .subject("SalesOrder", "orderId")
 *     .number("commerce.order_document_header", "orderNo")
 *     .party("customer", "commerce.order_document_header", "customerCode")
 *     .facts("commerce.order_document_header", "orderNo", "orderedTime", "warehouseName")
 *     .table("commerce.order_document_lines", "lineNo", "sku", "productName", "quantity", "unitPrice", "lineAmount")
 *     .totals("commerce.order_document_header", "totalAmount")
 *     .note("thanks"));
 * }</pre>
 *
 * <p>Texts: the title is the message {@code document.<id>}; a party's, a text's and a note's are
 * {@code document.<id>.<key>} (all required in every language of the application); a column's label is
 * {@code document.<id>.<column>} when there is one, else the template's own column text.
 *
 * @param id            the layout's name, lowercase with dots such as {@code fin.invoice}
 * @param permissions   what issuing and reading it needs besides the platform's own permissions (never empty)
 * @param subjectEntity the entity a document is about, or null; issued documents are listed by it
 * @param subjectParam  the parameter giving the subject's id (required with {@code subjectEntity})
 * @param number        the column giving the document's number (file name, list), or null
 * @param blocks        what the document shows, top to bottom
 */
public record DocumentLayout(String id, List<String> permissions, String subjectEntity, String subjectParam,
    Column number, List<Block> blocks) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** A template's result column. */
    public record Column(String template, String column) {
        public Column {
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(column, "column must not be null");
        }
    }

    /** What a document shows: one block of its page. */
    public sealed interface Block permits Party, Facts, Table, Totals, Text, Note {

        /** The template the block reads, or null for a note. */
        String template();

        /** The columns it shows. */
        List<String> columns();

        /** Whether its template must return exactly one row. */
        default boolean singleRow() {
            return true;
        }
    }

    /** A name and address: the non-empty columns of one row as lines under the label {@code key}. */
    public record Party(String key, String template, List<String> columns) implements Block {
        public Party {
            columns = List.copyOf(columns);
        }
    }

    /** Labelled values of one row, such as the number, the date and the terms. */
    public record Facts(String template, List<String> columns) implements Block {
        public Facts {
            columns = List.copyOf(columns);
        }
    }

    /** The rows of a template as a table, its header repeated on every page. */
    public record Table(String template, List<String> columns) implements Block {
        public Table {
            columns = List.copyOf(columns);
        }

        @Override
        public boolean singleRow() {
            return false;
        }
    }

    /** Labelled amounts of one row, right aligned; the last one is the total and stands out. */
    public record Totals(String template, List<String> columns) implements Block {
        public Totals {
            columns = List.copyOf(columns);
        }
    }

    /** A paragraph from one column of one row, under the heading {@code key}, such as payment instructions. */
    public record Text(String key, String template, String column) implements Block {
        @Override
        public List<String> columns() {
            return List.of(column);
        }
    }

    /** A fixed paragraph: the message {@code document.<id>.<key>}, taken as it reads when the document is issued. */
    public record Note(String key) implements Block {
        @Override
        public String template() {
            return null;
        }

        @Override
        public List<String> columns() {
            return List.of();
        }
    }

    public DocumentLayout {
        Objects.requireNonNull(id, "id must not be null");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Document layout id '" + id
                + "' must be dotted lowercase words such as fin.invoice");
        }
        permissions = List.copyOf(permissions);
        blocks = List.copyOf(blocks);
        if ((subjectEntity == null) != (subjectParam == null)) {
            throw new IllegalArgumentException("Document layout " + id + ": a subject needs its entity and parameter");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Block block : blocks) {
            String key = switch (block) {
                case Party p -> p.key();
                case Text t -> t.key();
                case Note n -> n.key();
                default -> null;
            };
            if (key != null) {
                if (!KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("Document layout " + id + ": key '" + key
                        + "' must be a letter followed by letters, digits or _");
                }
                if (!keys.add(key)) {
                    throw new IllegalArgumentException("Document layout " + id + ": key '" + key + "' is used twice");
                }
            }
            if (block.template() != null && block.columns().isEmpty()) {
                throw new IllegalArgumentException("Document layout " + id + ": a block of " + block.template()
                    + " shows no columns");
            }
        }
    }

    public static DocumentLayout define(String id, Consumer<Builder> spec) {
        Builder builder = new Builder();
        spec.accept(builder);
        return new DocumentLayout(id, builder.permissions, builder.subjectEntity, builder.subjectParam,
            builder.number, builder.blocks);
    }

    /** The message of the title. */
    public String titleKey() {
        return "document." + id;
    }

    /** The message of a block's label or a column's label. */
    public String labelKey(String key) {
        return "document." + id + "." + key;
    }

    /** The messages every language of the application must have: the title, and the parties', texts' and notes'. */
    public List<String> requiredMessages() {
        List<String> keys = new ArrayList<>();
        keys.add(titleKey());
        for (Block block : blocks) {
            switch (block) {
                case Party p -> keys.add(labelKey(p.key()));
                case Text t -> keys.add(labelKey(t.key()));
                case Note n -> keys.add(labelKey(n.key()));
                default -> { }
            }
        }
        return keys;
    }

    /** The templates the layout reads, in the order of first use, the number's included. */
    public List<String> templates() {
        Set<String> templates = new LinkedHashSet<>();
        for (Block block : blocks) {
            if (block.template() != null) {
                templates.add(block.template());
            }
        }
        if (number != null) {
            templates.add(number.template());
        }
        return List.copyOf(templates);
    }

    /** Whether the template is read for one row only (a party, facts, totals, text or the number). */
    public boolean singleRow(String template) {
        if (number != null && number.template().equals(template)) {
            return true;
        }
        return blocks.stream().anyMatch(block -> template.equals(block.template()) && block.singleRow());
    }

    /** The columns of a template the layout shows, the number's included, in the order of first use. */
    public List<String> columnsOf(String template) {
        Set<String> columns = new LinkedHashSet<>();
        for (Block block : blocks) {
            if (template.equals(block.template())) {
                columns.addAll(block.columns());
            }
        }
        if (number != null && number.template().equals(template)) {
            columns.add(number.column());
        }
        return List.copyOf(columns);
    }

    /**
     * The layout's version: SHA-256 of its canonical description. Any change to what it shows or needs gives a new
     * version; issued documents keep the version they were laid out with.
     */
    public String version() {
        return ContentHash.of(describe());
    }

    /** The canonical description the version is computed from, also archived with every issued document. */
    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("id", id);
        description.put("permissions", permissions);
        description.put("subjectEntity", subjectEntity);
        description.put("subjectParam", subjectParam);
        description.put("number", number == null ? null : List.of(number.template(), number.column()));
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Block block : blocks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("block", block.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
            switch (block) {
                case Party p -> entry.put("key", p.key());
                case Text t -> entry.put("key", t.key());
                case Note n -> entry.put("key", n.key());
                default -> { }
            }
            entry.put("template", block.template());
            entry.put("columns", block.columns());
            shown.add(entry);
        }
        description.put("blocks", shown);
        return description;
    }

    /** Declaration of a {@link DocumentLayout}. */
    public static final class Builder {
        private final List<String> permissions = new ArrayList<>();
        private final List<Block> blocks = new ArrayList<>();
        private String subjectEntity;
        private String subjectParam;
        private Column number;

        private Builder() {}

        /** Issuing and reading the document need these, besides the templates' own. */
        public Builder permissions(String... codes) {
            permissions.addAll(List.of(codes));
            return this;
        }

        /** The entity a document is about and the parameter that names it: documents are listed by it. */
        public Builder subject(String entity, String param) {
            this.subjectEntity = entity;
            this.subjectParam = param;
            return this;
        }

        /** The document's number: a column of a single-row template. */
        public Builder number(String template, String column) {
            this.number = new Column(template, column);
            return this;
        }

        public Builder party(String key, String template, String... columns) {
            blocks.add(new Party(key, template, List.of(columns)));
            return this;
        }

        public Builder facts(String template, String... columns) {
            blocks.add(new Facts(template, List.of(columns)));
            return this;
        }

        public Builder table(String template, String... columns) {
            blocks.add(new Table(template, List.of(columns)));
            return this;
        }

        public Builder totals(String template, String... columns) {
            blocks.add(new Totals(template, List.of(columns)));
            return this;
        }

        public Builder text(String key, String template, String column) {
            blocks.add(new Text(key, template, column));
            return this;
        }

        public Builder note(String key) {
            blocks.add(new Note(key));
            return this;
        }
    }
}
