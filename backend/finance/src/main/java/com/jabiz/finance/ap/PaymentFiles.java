package com.jabiz.finance.ap;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.calc.CheckFiles;
import com.jabiz.finance.calc.NachaValidator;
import com.jabiz.finance.calc.NachaWriter;
import com.jabiz.finance.calc.WireFile;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The files a released payment run gives the bank (FIN-AP-013; docs/finance/00-design.md section 9):
 * <ul>
 *   <li>{@code NACHA}: an ACH run's file, a CCD batch for businesses and a PPD batch for persons, checked by
 *       {@link NachaValidator} before it is kept;</li>
 *   <li>{@code CHECKS} and {@code POSITIVE_PAY}: a check run's print file and the file the bank compares presented
 *       checks with (voided checks marked {@code V});</li>
 *   <li>{@code WIRE}: a wire run's instructions, a CSV of the wires with the accounts in full ({@link WireFile}).</li>
 * </ul>
 * Each is kept exactly as made, with its hash ({@code FILE_ARCHIVE}), and read only by who releases payments: the files hold the account numbers in full. A run has one active file of a kind;
 * a file the bank refused is cancelled, with why, before another is made.
 */
public final class PaymentFiles {

    public static final String GENERATE = "FIN_PAYMENT_FILE_GENERATE";
    public static final String CANCEL = "FIN_PAYMENT_FILE_CANCEL";

    public static final String NOT_RELEASED = "FIN_PAYMENT_RUN_NOT_RELEASED";
    public static final String WRONG_KIND = "FIN_PAYMENT_FILE_KIND";
    public static final String EXISTS = "FIN_PAYMENT_FILE_EXISTS";
    public static final String NOTHING = "FIN_PAYMENT_FILE_EMPTY";
    public static final String NO_ACH = "FIN_PAYMENT_FILE_NO_ACH_COMPANY";
    public static final String INVALID = "FIN_PAYMENT_FILE_INVALID";
    public static final String NOT_ACTIVE = "FIN_PAYMENT_FILE_NOT_ACTIVE";
    public static final String DAY_LIMIT = "FIN_PAYMENT_FILE_DAY_LIMIT";

    public record GenerateInput(@NotNull UUID runId, @NotBlank @Size(max = 15) String fileKind) {}

    public record FileOutput(String paymentFileId, String fileKind, String fileName, String generatedFileId,
        String sha256, int entryCount, BigDecimal total, String status) {}

    public record CancelInput(@NotNull UUID paymentFileId, @NotBlank @Size(max = 500) String reason) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RUN_ID = "runId";
    static final String RUN = "run";
    static final String PAYMENTS = "payments";
    static final String FILES = "files";
    static final String BANKS = "banks";
    static final String VENDORS = "vendors";
    static final String VENDOR_BANKS = "vendorBanks";
    static final String APPLICATIONS = "applications";
    static final String ARCHIVE_INPUT = "archiveInput";
    static final String TODAY = "today";
    static final String FILES_TODAY = "filesToday";
    static final String ARCHIVED = "archived";
    static final String MADE = "made";
    static final String FILE = "file";

    /** What a file holds before it is kept: its name, entries and total. */
    record Made(String fileName, int entryCount, BigDecimal total) {}

    public static ProcessDefinition<GenerateInput, FileOutput, ProcessContext> generateProcess(BookingTime booking) {
        return ProcessDefinition.define(GENERATE, 1, GenerateInput.class, FileOutput.class, ProcessContext.class,
            pb -> pb
                .description("Makes a released payment run's file for the bank and keeps it exactly as made.")
                .permissions(FinancePermissions.PAYMENT_RELEASE)
                .actsOn(PaymentEntities.RUN, RUN_ID, a -> a.whenField("status", PaymentEntities.RELEASED))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = PaymentProcesses.withInput(start, input);
                    ctx.put(RUN_ID, input.runId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, FileOutput.class))
                .step("Load the run", LoadEntity.by(PaymentEntities.RUN_DATASET, RUN_ID, RUN))
                .step("Load its payments", QueryEntities.of(PaymentEntities.PAYMENT_DATASET,
                    ctx -> byRun(ctx), PAYMENTS))
                .step("Load its files", QueryEntities.of(PaymentEntities.FILE_DATASET, ctx -> byRun(ctx), FILES))
                .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("bankCode",
                        run(ctx).get("bankCode"))).limit(1).build(), BANKS))
                // NACHA files of one bank and day are told apart by the modifier A to Z.
                .compute("Today", (metadata, ctx) -> ctx.put(TODAY, booking.dateOf(ctx.opTime())))
                .step("Load the bank's files of today", QueryEntities.of(PaymentEntities.FILE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("bankCode", run(ctx).get("bankCode")),
                        new QueryPredicate.Eq("generatedDate", ctx.get(TODAY)),
                        new QueryPredicate.Eq("fileKind", PaymentEntities.NACHA)))).limit(100).build(), FILES_TODAY))
                .step("Load the vendors", QueryEntities.of(ApEntities.VENDOR_DATASET,
                    ctx -> byVendors(ctx), VENDORS))
                .step("Load the accounts paid to", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                    ctx -> paidTo(ctx), VENDOR_BANKS))
                .step("Load what the payments paid", QueryEntities.of(BillEntities.APPLICATION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("sourceId", new ArrayList<>(
                        PaymentProcesses.list(ctx, PAYMENTS).stream().map(p -> (Object) String.valueOf(p.id()))
                            .toList()))).limit(5000).build(), APPLICATIONS))
                .compute("Make the file", (metadata, ctx) -> make(ctx, booking))
                .step("Keep it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(ARCHIVE_INPUT),
                    GeneratedFileProcesses.ARCHIVE, 1, ctx -> ctx.get(ARCHIVE_INPUT), ARCHIVED))
                .compute("Record it", (metadata, ctx) -> record(ctx)));
    }

    static void make(ProcessContext ctx, BookingTime booking) {
        GenerateInput input = ctx.get(PaymentProcesses.INPUT, GenerateInput.class);
        EntityInstance run = run(ctx);
        String kind = input.fileKind().trim().toUpperCase(java.util.Locale.ROOT);
        if (!PaymentEntities.RELEASED.equals(run.get("status"))) {
            ctx.reject(new Violation("runId", NOT_RELEASED, run.get("runNo") + " is " + run.get("status")
                + ": files are made of released runs", Map.of("runNo", (Object) run.get("runNo"),
                "status", (Object) run.get("status"))));
            return;
        }
        String method = run.get("method");
        boolean fits = switch (kind) {
            case PaymentEntities.NACHA -> "ACH".equals(method);
            case PaymentEntities.CHECK_FILE, PaymentEntities.POSITIVE_PAY -> "CHECK".equals(method);
            case PaymentEntities.WIRE -> "WIRE".equals(method);
            default -> false;
        };
        if (!fits) {
            ctx.reject(new Violation("fileKind", WRONG_KIND, "A " + method + " run has no file " + kind,
                Map.of("fileKind", kind, "method", method)));
            return;
        }
        EntityInstance active = PaymentProcesses.list(ctx, FILES).stream()
            .filter(f -> kind.equals(f.get("fileKind")) && PaymentEntities.ACTIVE.equals(f.get("status")))
            .findFirst().orElse(null);
        if (active != null) {
            ctx.reject(new Violation("fileKind", EXISTS, run.get("runNo") + " has the file " + active.get("fileName")
                + ": cancel it, with why, before making another", Map.of("fileName", (Object) active.get("fileName"))));
            return;
        }
        List<EntityInstance> payments = new ArrayList<>(PaymentProcesses.list(ctx, PAYMENTS));
        payments.sort(Comparator.comparing(p -> (String) p.get("paymentNo")));
        // Positive pay tells the bank of voided checks too; the others carry what is to be paid.
        List<EntityInstance> paid = payments.stream().filter(p -> PaymentEntities.POSTED.equals(p.get("status")))
            .toList();
        if ((PaymentEntities.POSITIVE_PAY.equals(kind) ? payments : paid).isEmpty()) {
            ctx.reject(new Violation("runId", NOTHING, run.get("runNo") + " has no payments to put in a file",
                Map.of("runNo", (Object) run.get("runNo"))));
            return;
        }
        EntityInstance bank = PaymentProcesses.list(ctx, BANKS).getFirst();
        String runNo = run.get("runNo");
        BigDecimal total = paid.stream().map(p -> (BigDecimal) p.get("amount")).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        switch (kind) {
            case PaymentEntities.NACHA -> {
                if (bank.get("achCompanyId") == null || bank.get("achCompanyName") == null) {
                    ctx.reject(new Violation("runId", NO_ACH, "The bank account " + bank.get("bankCode")
                        + " has no ACH company identification and name", Map.of("bankCode",
                        (Object) bank.get("bankCode"))));
                    return;
                }
                EntityInstance unwritable = paid.stream().filter(p -> !NachaWriter.writable(p.get("payee")))
                    .findFirst().orElse(null);
                if (unwritable != null) {
                    ctx.reject(new Violation("runId", INVALID, "The payee of " + unwritable.get("paymentNo")
                        + " has no letters or digits an ACH file carries", Map.of("runNo", runNo)));
                    return;
                }
                long madeToday = PaymentProcesses.list(ctx, FILES_TODAY).size();
                if (madeToday >= 26) {
                    ctx.reject(new Violation("runId", DAY_LIMIT, "The bank account " + bank.get("bankCode")
                        + " has 26 ACH files today, A to Z: the next is made tomorrow", Map.of("bankCode",
                        (Object) bank.get("bankCode"))));
                    return;
                }
                String content = nacha(ctx, run, bank, paid, booking, (char) ('A' + madeToday));
                NachaValidator.Result checked = NachaValidator.validate(content);
                if (!checked.valid() || checked.totalCredit().compareTo(total) != 0
                    || checked.entries() != paid.size()) {
                    ctx.reject(new Violation("runId", INVALID, "The ACH file is not well formed: "
                        + String.join("; ", checked.problems()), Map.of("runNo", runNo)));
                    return;
                }
                archive(ctx, run, runNo + "-ACH.txt", "text/plain", content, new Made(runNo + "-ACH.txt",
                    checked.entries(), checked.totalCredit()));
            }
            case PaymentEntities.CHECK_FILE -> {
                String content = CheckFiles.printFile(checks(ctx, paid));
                archive(ctx, run, runNo + "-checks.csv", "text/csv", content, new Made(runNo + "-checks.csv",
                    paid.size(), total));
            }
            case PaymentEntities.POSITIVE_PAY -> {
                String content = CheckFiles.positivePay(bank.get("accountNumber"), checks(ctx, payments));
                archive(ctx, run, runNo + "-positive-pay.csv", "text/csv", content,
                    new Made(runNo + "-positive-pay.csv", payments.size(), total));
            }
            default -> {
                List<WireFile.Wire> wires = new ArrayList<>();
                for (EntityInstance payment : paid) {
                    EntityInstance account = paidTo(ctx, payment);
                    wires.add(new WireFile.Wire(payment.get("paymentNo"), payment.get("paymentDate"),
                        payment.get("amount"), payment.get("payee"), account.get("bankName"),
                        account.get("routingNumber"), account.get("accountNumber"),
                        payment.get("paymentNo") + " " + runNo));
                }
                String content = WireFile.write(bank.get("routingNumber"), bank.get("accountNumber"),
                    bank.get("currency"), wires);
                archive(ctx, run, runNo + "-wires.csv", "text/csv", content, new Made(runNo + "-wires.csv",
                    paid.size(), total));
            }
        }
    }

    /** One batch of each class: CCD for businesses, PPD for individuals and sole proprietors. */
    private static String nacha(ProcessContext ctx, EntityInstance run, EntityInstance bank,
        List<EntityInstance> paid, BookingTime booking, char modifier) {
        Map<String, EntityInstance> vendors = new LinkedHashMap<>();
        PaymentProcesses.list(ctx, VENDORS).forEach(v -> vendors.put(v.get("vendorCode"), v));
        Map<String, List<NachaWriter.Entry>> bySec = new LinkedHashMap<>();
        for (EntityInstance payment : paid) {
            EntityInstance account = paidTo(ctx, payment);
            EntityInstance vendor = vendors.get(payment.get("vendorCode"));
            String sec = vendor != null && "INDIVIDUAL".equals(vendor.get("entityType")) ? NachaWriter.PPD
                : NachaWriter.CCD;
            bySec.computeIfAbsent(sec, s -> new ArrayList<>()).add(new NachaWriter.Entry(
                NachaWriter.creditCode(account.get("accountType")), account.get("routingNumber"),
                account.get("accountNumber"), payment.get("amount"), payment.get("paymentNo"),
                payment.get("payee")));
        }
        List<NachaWriter.Batch> batches = new ArrayList<>();
        for (String sec : List.of(NachaWriter.CCD, NachaWriter.PPD)) {
            if (bySec.containsKey(sec)) {
                batches.add(new NachaWriter.Batch(sec, bank.get("achCompanyName"), bank.get("achCompanyId"),
                    "VENDOR PAY", run.get("paymentDate"), bank.get("routingNumber"), bySec.get(sec)));
            }
        }
        return NachaWriter.write(new NachaWriter.FileHeader(bank.get("routingNumber"), bank.get("bankName"),
            bank.get("achCompanyId"), bank.get("achCompanyName"),
            LocalDateTime.ofInstant(ctx.opTime(), booking.zone()), modifier), batches);
    }

    /**
     * The vendor's account a payment went to. ACH and wire runs hold only payments to vendors' approved accounts
     * (other payments are refused there), so one is always found.
     */
    private static EntityInstance paidTo(ProcessContext ctx, EntityInstance payment) {
        return PaymentProcesses.list(ctx, VENDOR_BANKS).stream()
            .filter(b -> Objects.equals(PaymentProcesses.uuid(b.id()),
                PaymentProcesses.uuid(payment.get("vendorBankAccountId"))))
            .findFirst().orElseThrow(() -> new IllegalStateException(payment.get("paymentNo")
                + " names no vendor bank account"));
    }

    private static List<CheckFiles.Check> checks(ProcessContext ctx, List<EntityInstance> payments) {
        Map<String, Integer> paidBills = new LinkedHashMap<>();
        for (EntityInstance application : PaymentProcesses.list(ctx, APPLICATIONS)) {
            if (application.get("reversesApplicationId") == null) {
                paidBills.merge(application.get("sourceId"), 1, Integer::sum);
            }
        }
        List<CheckFiles.Check> checks = new ArrayList<>();
        for (EntityInstance payment : payments) {
            int bills = paidBills.getOrDefault(String.valueOf(payment.id()), 0);
            String memo = bills == 0 ? (String) payment.get("runNo")
                : payment.get("runNo") + ", " + bills + (bills == 1 ? " bill" : " bills");
            checks.add(new CheckFiles.Check(payment.get("checkNo"), payment.get("paymentDate"), payment.get("payee"),
                payment.get("amount"), payment.get("paymentNo"), memo,
                PaymentEntities.VOID.equals(payment.get("status"))));
        }
        return checks;
    }

    private static void archive(ProcessContext ctx, EntityInstance run, String name, String mediaType,
        String content, Made made) {
        ctx.put(MADE, made);
        ctx.put(ARCHIVE_INPUT, new GeneratedFileProcesses.ArchiveInput(name, mediaType,
            content.getBytes(StandardCharsets.UTF_8), List.of(FinancePermissions.PAYMENT_RELEASE), PaymentEntities.RUN,
            String.valueOf(run.id())));
    }

    static void record(ProcessContext ctx) {
        if (!ctx.contains(MADE)) {
            return;
        }
        GenerateInput input = ctx.get(PaymentProcesses.INPUT, GenerateInput.class);
        EntityInstance run = run(ctx);
        Made made = ctx.get(MADE, Made.class);
        if (!ctx.contains(ARCHIVED)) {
            return;
        }
        GeneratedFileProcesses.ArchiveOutput archived = ctx.get(ARCHIVED, GeneratedFileProcesses.ArchiveOutput.class);
        String fileId = archived.fileId();
        String sha256 = archived.sha256();
        String kind = input.fileKind().trim().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("runId", run.id());
        file.put("runNo", run.get("runNo"));
        file.put("fileKind", kind);
        file.put("bankCode", run.get("bankCode"));
        file.put("generatedDate", ctx.get(TODAY));
        file.put("generatedFileId", fileId);
        file.put("fileName", made.fileName());
        file.put("sha256", sha256);
        file.put("entryCount", BigDecimal.valueOf(made.entryCount()));
        file.put("total", made.total());
        file.put("status", PaymentEntities.ACTIVE);
        file.put("generatedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(PaymentEntities.FILE, file);
        ctx.put(OUTPUT, new FileOutput(String.valueOf(id), kind, made.fileName(), fileId, sha256, made.entryCount(),
            made.total(), PaymentEntities.ACTIVE));
    }

    public static final ProcessDefinition<CancelInput, FileOutput, ProcessContext> CANCEL_PROCESS =
        ProcessDefinition.define(CANCEL, 1, CancelInput.class, FileOutput.class, ProcessContext.class, pb -> pb
            .description("Cancels a payment file the bank did not take, with why, so another can be made.")
            .permissions(FinancePermissions.PAYMENT_RELEASE)
            .actsOn(PaymentEntities.FILE, "paymentFileId", a -> a.whenField("status", PaymentEntities.ACTIVE))
            .contextFactory((start, input) -> {
                ProcessContext ctx = PaymentProcesses.withInput(start, input);
                ctx.put("paymentFileId", input.paymentFileId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, FileOutput.class))
            .step("Load the file", LoadEntity.by(PaymentEntities.FILE_DATASET, "paymentFileId", FILE))
            .compute("Cancel it", (metadata, ctx) -> {
                EntityInstance file = ctx.get(FILE, EntityInstance.class);
                if (!PaymentEntities.ACTIVE.equals(file.get("status"))) {
                    ctx.reject(new Violation("paymentFileId", NOT_ACTIVE, file.get("fileName") + " is "
                        + file.get("status"), Map.of("fileName", (Object) file.get("fileName"))));
                    return;
                }
                ctx.changes().update(PaymentEntities.FILE, file.id(), file.version(), Map.of(
                    "status", PaymentEntities.CANCELLED, "cancelledBy", ctx.request().actorId(),
                    "cancelReason", ctx.get(PaymentProcesses.INPUT, CancelInput.class).reason().trim()));
                BigDecimal count = file.get("entryCount");
                ctx.put(OUTPUT, new FileOutput(String.valueOf(file.id()), file.get("fileKind"), file.get("fileName"),
                    file.get("generatedFileId"), file.get("sha256"), count == null ? 0 : count.intValue(),
                    file.get("total"), PaymentEntities.CANCELLED));
            }));

    private static EntityQuery byRun(ProcessContext ctx) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("runId", run(ctx).id())).limit(5000).build();
    }

    private static EntityQuery byVendors(ProcessContext ctx) {
        List<Object> codes = PaymentProcesses.list(ctx, PAYMENTS).stream().map(p -> (Object) p.get("vendorCode"))
            .filter(Objects::nonNull).distinct().toList();
        return EntityQuery.builder().where(new QueryPredicate.In("vendorCode", new ArrayList<>(codes)))
            .limit(Math.max(1, codes.size() * 50)).build();
    }

    /** The vendors' accounts the payments went to, by id: replaced since, they are still the ones paid. */
    private static EntityQuery paidTo(ProcessContext ctx) {
        List<Object> ids = PaymentProcesses.list(ctx, PAYMENTS).stream().map(p -> (Object) p.get("vendorBankAccountId"))
            .filter(Objects::nonNull).distinct().toList();
        return EntityQuery.builder().where(new QueryPredicate.In("bankAccountId", new ArrayList<>(ids)))
            .limit(Math.max(1, ids.size())).build();
    }

    private static EntityInstance run(ProcessContext ctx) {
        return ctx.get(RUN, EntityInstance.class);
    }

    private PaymentFiles() {}
}
