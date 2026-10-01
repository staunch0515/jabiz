package com.jabiz.finance.io;

import com.jabiz.finance.tax.TaxProcesses;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a tax code file reads (FIN-DI-001, FIN-TX-001; ROADMAP F3a). Explicit columns win: {@code kind},
 * {@code reason}, {@code state}, {@code jurisdictions} ("TX:6.25;TX-AUSTIN-LOCAL:2.00"), {@code certificate_required},
 * {@code charge_code}. The sample company's {@code tax-codes.csv} has only a code, a description, a combined rate and
 * a note, and reads so:
 * <ul>
 *   <li>a code starting with a state ("TX-AUSTIN") belongs to that state;</li>
 *   <li>a rate above zero is taxable; the note "state 6.25% + local 2.00%" splits it into the state's jurisdiction
 *       ({@code TX}) and the code's local one ({@code TX-AUSTIN-LOCAL}), which must add up to the rate; without such
 *       a note one jurisdiction named as the code carries the rate;</li>
 *   <li>a zero rate whose note or description speaks of a certificate is an exemption that needs one (resale when it
 *       says so);</li>
 *   <li>any other zero rate is non-taxable: a service, an export, or a state without sales tax.</li>
 * </ul>
 * Pure; a row that does not read is the row's error.
 */
final class TaxCodeRows {

    /** One row of the file, as text. */
    record Row(String code, String description, BigDecimal ratePercent, String note, String kind, String reason,
        String state, String jurisdictions, Boolean certificateRequired, String chargeCode) {}

    private static final Pattern STATE_PREFIX = Pattern.compile("^([A-Z]{2})-");
    private static final Pattern PART = Pattern.compile("(state|county|city|local|transit|special)\\s+([0-9.]+)\\s*%",
        Pattern.CASE_INSENSITIVE);

    private TaxCodeRows() {}

    static TaxProcesses.TaxCodeInput read(Row row, LocalDate ratesFrom) {
        String code = row.code().trim().toUpperCase(Locale.ROOT);
        String description = row.description() == null || row.description().isBlank() ? code : row.description().trim();
        String text = ((row.note() == null ? "" : row.note()) + " " + description).toLowerCase(Locale.ROOT);
        BigDecimal rate = row.ratePercent() == null ? BigDecimal.ZERO : row.ratePercent();
        String state = upper(row.state());
        if (state == null) {
            Matcher prefix = STATE_PREFIX.matcher(code);
            state = prefix.find() ? prefix.group(1) : null;
        }
        String kind = upper(row.kind());
        if (kind == null) {
            kind = rate.signum() > 0 ? "TAXABLE" : text.contains("certificate") ? "EXEMPT" : "NON_TAXABLE";
        }
        Boolean certificate = row.certificateRequired();
        if (certificate == null) {
            certificate = "EXEMPT".equals(kind) && text.contains("certificate");
        }
        String reason = upper(row.reason());
        if (reason == null && !"TAXABLE".equals(kind)) {
            reason = text.contains("resale") ? "RESALE"
                : text.contains("service") ? "NON_TAXABLE_SERVICE"
                : text.contains("export") ? "EXPORT"
                : "EXEMPT".equals(kind) ? "OTHER" : "NO_SALES_TAX";
        }
        List<TaxProcesses.JurisdictionPart> parts = new ArrayList<>();
        if ("TAXABLE".equals(kind)) {
            if (row.jurisdictions() != null && !row.jurisdictions().isBlank()) {
                for (String item : row.jurisdictions().split(";")) {
                    String[] pair = item.trim().split(":");
                    if (pair.length != 2) {
                        throw new IllegalArgumentException("Jurisdictions are written as CODE:RATE;CODE:RATE, not \""
                            + row.jurisdictions() + "\"");
                    }
                    String jurisdiction = pair[0].trim().toUpperCase(Locale.ROOT);
                    parts.add(new TaxProcesses.JurisdictionPart(jurisdiction, jurisdiction, "SPECIAL", state,
                        new BigDecimal(pair[1].trim())));
                }
            } else {
                Matcher m = PART.matcher(row.note() == null ? "" : row.note());
                while (m.find()) {
                    parts.add(part(code, description, state, m.group(1).toLowerCase(Locale.ROOT),
                        new BigDecimal(m.group(2))));
                }
                if (parts.isEmpty()) {
                    parts.add(new TaxProcesses.JurisdictionPart(code, description, "SPECIAL", state, rate));
                }
            }
            if (state == null) {
                throw new IllegalArgumentException("The taxable code " + code + " names no state");
            }
            BigDecimal sum = parts.stream().map(TaxProcesses.JurisdictionPart::ratePercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (row.ratePercent() != null && sum.compareTo(rate) != 0) {
                throw new IllegalArgumentException("The jurisdictions of " + code + " add up to " + sum.toPlainString()
                    + "%, the code's rate is " + rate.toPlainString() + "%");
            }
        }
        return new TaxProcesses.TaxCodeInput(code, description, kind, reason, state, parts, certificate,
            upper(row.chargeCode()), true, parts.isEmpty() ? null : ratesFrom);
    }

    private static TaxProcesses.JurisdictionPart part(String code, String description, String state, String level,
        BigDecimal rate) {
        return switch (level) {
            case "state" -> new TaxProcesses.JurisdictionPart(state, state + " state", "STATE", state, rate);
            case "county" -> new TaxProcesses.JurisdictionPart(code + "-COUNTY", description + " (county)", "COUNTY",
                state, rate);
            case "city", "local" -> new TaxProcesses.JurisdictionPart(code + "-LOCAL", description + " (local)", "CITY",
                state, rate);
            default -> new TaxProcesses.JurisdictionPart(code + "-SPECIAL", description + " (special district)",
                "SPECIAL", state, rate);
        };
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
