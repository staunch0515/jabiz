package com.jabiz.finance.gl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The standard chart of accounts of a small US commercial company (FIN-GL-002), a resource the controller previews
 * and then copies into new books ({@code FIN_COA_TEMPLATE_PREVIEW} / {@code FIN_COA_TEMPLATE_APPLY}). Summary
 * accounts come before the accounts that roll up into them.
 */
public final class ChartTemplate {

    public static final String RESOURCE = "/finance/coa/us-small-business.csv";

    /** One proposed account; the types and sides as the chart files write them ({@code Asset}, {@code D}). */
    public record Line(String accountCode, String accountName, String financialType, String normalBalance,
        String statementLine, String parentCode, boolean summary, String controlClass, String cashFlowClass,
        boolean clearing) {}

    /**
     * Read when the class loads, which {@code GlConfig} makes happen at startup: never on a request thread, where
     * reading a file would block.
     */
    private static final List<Line> LINES = load();

    private ChartTemplate() {}

    public static List<Line> lines() {
        return LINES;
    }

    private static List<Line> load() {
        try (InputStream in = ChartTemplate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing chart template " + RESOURCE);
            }
            List<String> rows = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
            List<Line> lines = new ArrayList<>();
            for (String row : rows.subList(1, rows.size())) {
                List<String> f = Csv.fields(row);
                lines.add(new Line(f.get(0), f.get(1), AccountTypes.fromChart(f.get(2)),
                    AccountTypes.normalBalanceFromChart(f.get(3)), f.get(4), blank(f.get(5)),
                    Boolean.parseBoolean(f.get(6)), blank(f.get(7)), blank(f.get(8)), Boolean.parseBoolean(f.get(9))));
            }
            return List.copyOf(lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
