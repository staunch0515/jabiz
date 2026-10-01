package com.jabiz.document;

import com.jabiz.report.ReportColumn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything an issued document shows (docs/design/22-documents.md section 3): what each of its templates returned,
 * the texts of its labels in its language, its title and number. A writer only lays it out; the same content always
 * gives the same file.
 *
 * @param layoutId      the layout
 * @param layoutVersion its version when the content was read
 * @param title         the document's title in its language
 * @param number        the document's number, or null when the layout has none
 * @param company       who issues it (the PDF's author)
 * @param language      its language
 * @param issuedTime    when it was issued (or previewed); also the creation time of the PDF
 * @param preview       whether it is a preview, which is marked on every page and never archived
 * @param labels        texts of the layout's labels by message key ({@code document.<id>.<key>}), resolved
 * @param sources       by template id, what the template returned
 */
public record DocumentContent(String layoutId, String layoutVersion, String title, String number, String company,
    String language, Instant issuedTime, boolean preview, Map<String, String> labels, Map<String, Source> sources) {

    /**
     * What one template returned.
     *
     * @param templateId      the template
     * @param templateVersion its version
     * @param columns         its result columns, with their labels in the document's language
     * @param rows            its rows, each with one value per column
     */
    public record Source(String templateId, String templateVersion, List<ReportColumn> columns,
        List<List<Object>> rows) {

        public Source {
            Objects.requireNonNull(templateId, "templateId must not be null");
            columns = List.copyOf(columns);
            List<List<Object>> copied = new ArrayList<>(rows.size());
            for (List<Object> row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException("A row of " + templateId + " has " + row.size()
                        + " values for " + columns.size() + " columns");
                }
                copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
            rows = Collections.unmodifiableList(copied);
        }

        public Optional<Integer> index(String column) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equalsIgnoreCase(column)) {
                    return Optional.of(i);
                }
            }
            return Optional.empty();
        }

        public ReportColumn column(String column) {
            return columns.get(index(column).orElseThrow(() -> new IllegalArgumentException(
                templateId + " has no column " + column)));
        }

        /** The value of a column in the first row; null when there is none. */
        public Object first(String column) {
            return rows.isEmpty() ? null : rows.getFirst().get(index(column).orElseThrow(
                () -> new IllegalArgumentException(templateId + " has no column " + column)));
        }
    }

    public DocumentContent {
        Objects.requireNonNull(layoutId, "layoutId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(issuedTime, "issuedTime must not be null");
        company = company == null ? "" : company;
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    public Source source(String templateId) {
        Source source = sources.get(templateId);
        if (source == null) {
            throw new IllegalArgumentException("The document has nothing of template " + templateId);
        }
        return source;
    }

    /** A label's text, or the given default. */
    public String label(String key, String fallback) {
        return labels.getOrDefault(key, fallback);
    }
}
