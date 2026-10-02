package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes an ACH file in the NACHA format (FIN-AP-013): 94-character records, a file header, one batch per standard
 * entry class with its entries and control, the file control, and lines of nines to a multiple of ten records.
 * Credits only (service class 220): a vendor payment pays into the vendor's account. {@link NachaValidator} checks
 * what this writes without sharing its code.
 */
public final class NachaWriter {

    /** Corporate credit or debit: to a business. */
    public static final String CCD = "CCD";
    /** Prearranged payment and deposit: to a person's account. */
    public static final String PPD = "PPD";

    public static final String CHECKING_CREDIT = "22";
    public static final String SAVINGS_CREDIT = "32";

    static final int RECORD = 94;
    static final String CREDITS_ONLY = "220";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT);

    /**
     * @param destinationRouting the routing number of the company's bank, nine digits
     * @param destinationName    the bank's name
     * @param originId           the company's ACH identification its bank gave, ten characters
     * @param originName         the company's name
     * @param created            when the file is made, in the company's time
     * @param fileIdModifier     {@code A} to {@code Z}, telling apart files of one day
     */
    public record FileHeader(String destinationRouting, String destinationName, String originId, String originName,
        LocalDateTime created, char fileIdModifier) {

        public FileHeader {
            requireRouting(destinationRouting, "destinationRouting");
            Objects.requireNonNull(originId, "originId must not be null");
            Objects.requireNonNull(created, "created must not be null");
            if (fileIdModifier < 'A' || fileIdModifier > 'Z') {
                throw new IllegalArgumentException("fileIdModifier must be A to Z");
            }
        }
    }

    /**
     * @param secCode          {@link #CCD} or {@link #PPD}
     * @param companyName      the company as the vendors' statements show it
     * @param companyId        the company's ACH identification
     * @param entryDescription what the vendors' statements say, such as {@code VENDOR PAY}
     * @param effectiveDate    the day the payments are to settle
     * @param odfiRouting      the routing number of the company's bank; its first eight digits identify it
     */
    public record Batch(String secCode, String companyName, String companyId, String entryDescription,
        LocalDate effectiveDate, String odfiRouting, List<Entry> entries) {

        public Batch {
            if (!CCD.equals(secCode) && !PPD.equals(secCode)) {
                throw new IllegalArgumentException("secCode must be CCD or PPD");
            }
            requireRouting(odfiRouting, "odfiRouting");
            Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
            entries = List.copyOf(entries);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("a batch has entries");
            }
        }
    }

    /**
     * @param transactionCode {@link #CHECKING_CREDIT} or {@link #SAVINGS_CREDIT}
     * @param routing         the vendor's bank, nine digits
     * @param account         the vendor's account, up to 17 characters
     * @param amount          in dollars, more than zero, at most 99,999,999.99
     * @param idNumber        what the vendor's statement identifies the payment by, such as the payment number
     * @param name            the vendor
     */
    public record Entry(String transactionCode, String routing, String account, BigDecimal amount, String idNumber,
        String name) {

        public Entry {
            if (!CHECKING_CREDIT.equals(transactionCode) && !SAVINGS_CREDIT.equals(transactionCode)) {
                throw new IllegalArgumentException("transactionCode must be 22 or 32");
            }
            requireRouting(routing, "routing");
            if (account == null || account.isBlank() || account.length() > 17) {
                throw new IllegalArgumentException("account must be 1 to 17 characters");
            }
            if (amount == null || amount.signum() <= 0 || cents(amount) > 9_999_999_999L) {
                throw new IllegalArgumentException("amount must be more than zero and fit ten digits of cents");
            }
            if (!writable(name)) {
                throw new IllegalArgumentException("name must have letters or digits NACHA can carry");
            }
        }
    }

    /** The file: records of 94 characters, each ended by a line feed. */
    public static String write(FileHeader header, List<Batch> batches) {
        if (batches.isEmpty()) {
            throw new IllegalArgumentException("a file has batches");
        }
        StringBuilder out = new StringBuilder();
        line(out, "1" + "01" + " " + header.destinationRouting() + alpha(header.originId(), 10)
            + header.created().format(DATE) + header.created().format(TIME) + header.fileIdModifier() + "094" + "10"
            + "1" + alpha(header.destinationName(), 23) + alpha(header.originName(), 23) + alpha("", 8));
        int records = 1;
        long fileEntries = 0;
        long fileHash = 0;
        long fileCredit = 0;
        int batchNo = 0;
        for (Batch batch : batches) {
            batchNo++;
            String odfi = batch.odfiRouting().substring(0, 8);
            line(out, "5" + CREDITS_ONLY + alpha(batch.companyName(), 16) + alpha("", 20)
                + alpha(batch.companyId(), 10) + batch.secCode() + alpha(batch.entryDescription(), 10)
                + alpha("", 6) + batch.effectiveDate().format(DATE) + alpha("", 3) + "1" + odfi
                + number(batchNo, 7));
            records++;
            long hash = 0;
            long credit = 0;
            for (Entry entry : batch.entries()) {
                fileEntries++;
                line(out, "6" + entry.transactionCode() + entry.routing() + alpha(entry.account(), 17)
                    + number(cents(entry.amount()), 10) + alpha(entry.idNumber(), 15) + alpha(entry.name(), 22)
                    + alpha("", 2) + "0" + odfi + number(fileEntries, 7));
                records++;
                hash += Long.parseLong(entry.routing().substring(0, 8));
                credit += cents(entry.amount());
            }
            line(out, "8" + CREDITS_ONLY + number(batch.entries().size(), 6) + number(hash % 10_000_000_000L, 10)
                + number(0, 12) + number(credit, 12) + alpha(batch.companyId(), 10) + alpha("", 19) + alpha("", 6)
                + odfi + number(batchNo, 7));
            records++;
            fileHash += hash;
            fileCredit += credit;
        }
        records++;
        int blocks = (records + 9) / 10;
        line(out, "9" + number(batches.size(), 6) + number(blocks, 6) + number(fileEntries, 8)
            + number(fileHash % 10_000_000_000L, 10) + number(0, 12) + number(fileCredit, 12) + alpha("", 39));
        for (int i = records; i < blocks * 10; i++) {
            line(out, "9".repeat(RECORD));
        }
        return out.toString();
    }

    /** Whether a name keeps something to read once written as NACHA allows (ASCII, upper case, 22 characters). */
    public static boolean writable(String name) {
        return !alpha(name, 22).isBlank();
    }

    /** The transaction code of a credit to a checking or savings account. */
    public static String creditCode(String accountType) {
        return "SAVINGS".equals(accountType) ? SAVINGS_CREDIT : CHECKING_CREDIT;
    }

    static long cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    /**
     * Upper case, without accents, only the characters NACHA allows (printable ASCII), left-justified and cut to
     * the length.
     */
    static String alpha(String value, int length) {
        String text = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < text.length() && out.length() < length; i++) {
            char c = text.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E ? c : ' ');
        }
        while (out.length() < length) {
            out.append(' ');
        }
        return out.toString();
    }

    static String number(long value, int length) {
        String digits = Long.toString(value);
        if (value < 0 || digits.length() > length) {
            throw new IllegalArgumentException(value + " does not fit " + length + " digits");
        }
        return "0".repeat(length - digits.length()) + digits;
    }

    private static void line(StringBuilder out, String record) {
        if (record.length() != RECORD) {
            throw new IllegalStateException("A record of " + record.length() + " characters: " + record);
        }
        out.append(record).append('\n');
    }

    private static void requireRouting(String routing, String name) {
        if (routing == null || !routing.matches("\\d{9}")) {
            throw new IllegalArgumentException(name + " must be nine digits");
        }
    }

    private NachaWriter() {}
}
