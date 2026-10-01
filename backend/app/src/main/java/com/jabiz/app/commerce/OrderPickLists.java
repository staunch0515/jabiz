package com.jabiz.app.commerce;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.jabiz.app.commerce.CommerceEntities.ORDER;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_DATASET;

/**
 * The pick list of an order as a CSV file for the warehouse's scanners, the platform's example of a generated file
 * (docs/design/14-files.md section 10): {@code ORDER_PICK_LIST_ARCHIVE} makes it from the order's lines and keeps it with
 * {@code FILE_ARCHIVE}, so what the warehouse was given can be downloaded again exactly as made.
 */
public final class OrderPickLists {

    public static final String ARCHIVE = "ORDER_PICK_LIST_ARCHIVE";

    public record ArchiveInput(@NotBlank String orderId) {}

    public record ArchiveOutput(String fileId, String sha256) {}

    private static final String ORDER_ID = "orderId";
    private static final String ORDER_KEY = "order";
    private static final String LINES = "lines";
    private static final String CSV = "csv";
    private static final String KEPT = "kept";
    private static final List<String> COLUMNS = List.of("lineNo", "sku", "productName", "quantity");

    public static final ProcessDefinition<ArchiveInput, ArchiveOutput, ProcessContext> ARCHIVE_PROCESS =
        ProcessDefinition.define(ARCHIVE, 1, ArchiveInput.class, ArchiveOutput.class, ProcessContext.class, pb -> pb
            .description("Makes the pick list of a sales order as a CSV file and keeps it exactly as made.")
            .permissions("commerce.order.confirm")
            .actsOn(ORDER, ORDER_ID)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(ORDER_ID, input.orderId());
                return ctx;
            })
            .outputMapper(ctx -> {
                GeneratedFileProcesses.ArchiveOutput kept = ctx.get(KEPT, GeneratedFileProcesses.ArchiveOutput.class);
                return new ArchiveOutput(kept.fileId(), kept.sha256());
            })
            .step("Load the order", LoadEntity.by(ORDER_DATASET, ORDER_ID, ORDER_KEY))
            .step("Read its lines", RunTemplate.<ProcessContext>of(OrderConfirmations.LINES,
                ctx -> Map.of(ORDER_ID, ctx.get(ORDER_ID)), LINES))
            .compute("Make the CSV", (metadata, ctx) -> ctx.put(CSV, csv(ctx)))
            .step("Keep the file", CallProcess.<ProcessContext>of(GeneratedFileProcesses.ARCHIVE, 1,
                ctx -> new GeneratedFileProcesses.ArchiveInput(fileName(ctx.get(ORDER_KEY, EntityInstance.class)),
                    "text/csv", ctx.get(CSV, byte[].class), List.of("commerce.order.read"), ORDER,
                    ctx.get(ORDER_ID, String.class)), KEPT)));

    private OrderPickLists() {}

    @SuppressWarnings("unchecked")
    private static byte[] csv(ProcessContext ctx) {
        StringBuilder csv = new StringBuilder(String.join(",", COLUMNS)).append("\r\n");
        for (Map<String, Object> line : (List<Map<String, Object>>) ctx.get(LINES, List.class)) {
            csv.append(String.join(",", COLUMNS.stream().map(column -> cell(line.get(column))).toList()))
                .append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Text is quoted, and a leading = + - @ is defused so a spreadsheet never takes a product name for a formula.
    private static String cell(Object value) {
        if (!(value instanceof String text)) {
            return value == null ? "" : value.toString();
        }
        String safe = !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String fileName(EntityInstance order) {
        Object orderNo = order.get("orderNo");
        return "pick-list " + String.valueOf(orderNo).replaceAll("[^A-Za-z0-9._-]", "_") + ".csv";
    }
}
