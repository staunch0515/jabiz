package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * US sales tax of one document (FIN-TX-002, 003, 004; docs/finance/00-design.md section 8). Each line carries the tax
 * code that applies to it: the line's own (such as {@code NT} for a service) or else the code of the ship-to
 * jurisdiction, so the tax follows the destination. A line is
 * <ul>
 *   <li>non-taxable when its code says so (a service, a state without sales tax, an export);</li>
 *   <li>exempt when its code is an exemption that needs a certificate and the customer holds one for the code's
 *       state valid on the document date, or when the customer holds such a certificate for a taxable code's state;
 *       without a valid certificate the document is refused, or taxed under the code's charge code, as
 *       configured;</li>
 *   <li>taxable otherwise.</li>
 * </ul>
 * Tax is computed per jurisdiction on the sum of the document's taxable lines whose codes include it (two codes of
 * one state share the state's tax), rounded half away from zero to the cent, and allocated back to those lines by
 * the largest remainder so the lines add up to it. Every amount keeps how
 * it was computed: the base, the rate and the date the rate took effect (FIN-UI-007). Pure computation.
 */
public final class SalesTax {

    public enum Kind { TAXABLE, EXEMPT, NON_TAXABLE }

    /** What to do with a line whose code needs a certificate the customer does not hold on the date. */
    public enum OnMissingCertificate { BLOCK, CHARGE }

    /**
     * A tax code.
     *
     * @param reason              why an exempt or non-taxable line is so ({@code RESALE}, {@code EXPORT}, ...)
     * @param state               the state the code belongs to, if any: certificates are per state
     * @param jurisdictions       the jurisdictions whose rates add up to the code's rate; none for a code without tax
     * @param certificateRequired whether the exemption holds only with a valid certificate
     * @param chargeCode          the taxable code used instead when the certificate is missing and the policy charges
     */
    public record Code(String code, Kind kind, String reason, String state, List<String> jurisdictions,
        boolean certificateRequired, String chargeCode) {
        public Code {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            jurisdictions = jurisdictions == null ? List.of() : List.copyOf(jurisdictions);
        }
    }

    /** A jurisdiction's rate in percent from {@code from} to {@code to} (both inclusive; {@code to} null: open). */
    public record Rate(String jurisdiction, LocalDate from, LocalDate to, BigDecimal percent) {
        boolean covers(LocalDate date) {
            return !date.isBefore(from) && (to == null || !date.isAfter(to));
        }
    }

    /** A customer's exemption or resale certificate for a state; either date may be open. */
    public record Certificate(String state, String number, String type, LocalDate issued, LocalDate expires) {
        boolean validOn(LocalDate date) {
            return (issued == null || !date.isBefore(issued)) && (expires == null || !date.isAfter(expires));
        }
    }

    /** A line: its amount before tax (not negative) and the tax code that applies to it. */
    public record Line(BigDecimal amount, String taxCode) {}

    public record Request(LocalDate date, List<Line> lines, Map<String, Code> codes, List<Rate> rates,
        List<Certificate> certificates, OnMissingCertificate onMissing) {
        public Request {
            lines = List.copyOf(lines);
            codes = Map.copyOf(codes);
            rates = List.copyOf(rates);
            certificates = List.copyOf(certificates);
        }
    }

    /**
     * How a line came out.
     *
     * @param taxCode     the code it was taxed under (the charge code when a missing certificate was charged)
     * @param certificate the certificate that exempts it, if one does
     * @param tax         its share of the document's tax
     */
    public record LineResult(int line, String taxCode, Kind kind, String reason, String certificate, BigDecimal tax) {}

    /**
     * The tax of one jurisdiction on the document: the explanation of the amount.
     *
     * @param shares the tax allocated to each line of the document (zero for lines not in the base)
     */
    public record JurisdictionTax(String jurisdiction, BigDecimal base, BigDecimal percent, LocalDate rateFrom,
        BigDecimal tax, List<BigDecimal> shares) {}

    /** Why the document cannot be taxed; {@code line} is 0-based, -1 for the document. */
    public record Problem(int line, String code, String message, Map<String, Object> params) {}

    public record Result(List<LineResult> lines, List<JurisdictionTax> taxes, BigDecimal total,
        List<Problem> problems) {}

    public static final String UNKNOWN_CODE = "FIN_TAX_UNKNOWN_CODE";
    public static final String NO_RATE = "FIN_TAX_NO_RATE";
    public static final String CERTIFICATE_MISSING = "FIN_TAX_CERTIFICATE_MISSING";
    public static final String NEGATIVE_LINE = "FIN_TAX_NEGATIVE_LINE";

    private SalesTax() {}

    public static Result compute(Request request) {
        List<Problem> problems = new ArrayList<>();
        int size = request.lines().size();
        String[] codes = new String[size];
        Kind[] kinds = new Kind[size];
        String[] reasons = new String[size];
        String[] certificates = new String[size];
        for (int i = 0; i < size; i++) {
            Line line = request.lines().get(i);
            if (line.amount().signum() < 0) {
                problems.add(new Problem(i, NEGATIVE_LINE, "Line " + (i + 1) + " has a negative amount",
                    Map.of("line", i + 1)));
                continue;
            }
            Code code = request.codes().get(line.taxCode());
            if (code == null) {
                problems.add(new Problem(i, UNKNOWN_CODE, "Line " + (i + 1) + " has no tax code " + line.taxCode(),
                    params("line", i + 1, "taxCode", line.taxCode())));
                continue;
            }
            Certificate certificate = certificate(request, code.state());
            if (code.kind() == Kind.EXEMPT && code.certificateRequired() && certificate == null) {
                Code charged = request.onMissing() == OnMissingCertificate.CHARGE && code.chargeCode() != null
                    ? request.codes().get(code.chargeCode()) : null;
                if (charged == null || charged.kind() != Kind.TAXABLE) {
                    problems.add(new Problem(i, CERTIFICATE_MISSING, "Tax code " + code.code() + " needs a valid "
                        + "certificate for " + code.state() + " on " + request.date(),
                        params("line", i + 1, "taxCode", code.code(), "state", code.state(), "date", request.date())));
                    continue;
                }
                code = charged;
                certificate = null;
            }
            codes[i] = code.code();
            if (code.kind() == Kind.TAXABLE && certificate != null) {
                // The customer's certificate for the state exempts what would be taxed there.
                kinds[i] = Kind.EXEMPT;
                reasons[i] = certificate.type();
                certificates[i] = certificate.number();
            } else {
                kinds[i] = code.kind();
                reasons[i] = code.kind() == Kind.TAXABLE ? null : code.reason();
                certificates[i] = code.kind() == Kind.EXEMPT && certificate != null ? certificate.number() : null;
            }
        }
        // Taxable lines by jurisdiction, in the order the jurisdictions first appear.
        Map<String, List<Integer>> taxable = new LinkedHashMap<>();
        Map<String, String> namedBy = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            if (kinds[i] == Kind.TAXABLE) {
                for (String jurisdiction : request.codes().get(codes[i]).jurisdictions()) {
                    taxable.computeIfAbsent(jurisdiction, j -> new ArrayList<>()).add(i);
                    namedBy.putIfAbsent(jurisdiction, codes[i]);
                }
            }
        }
        List<JurisdictionTax> taxes = new ArrayList<>();
        BigDecimal[] lineTax = new BigDecimal[size];
        java.util.Arrays.fill(lineTax, Money.usd(BigDecimal.ZERO));
        taxable.forEach((jurisdiction, lines) -> {
            Rate rate = request.rates().stream()
                .filter(r -> r.jurisdiction().equals(jurisdiction) && r.covers(request.date()))
                .findFirst().orElse(null);
            if (rate == null) {
                problems.add(new Problem(-1, NO_RATE, "Jurisdiction " + jurisdiction + " of tax code "
                    + namedBy.get(jurisdiction) + " has no rate on " + request.date(), params("jurisdiction",
                    jurisdiction, "taxCode", namedBy.get(jurisdiction), "date", request.date())));
                return;
            }
            List<BigDecimal> weights = lines.stream().map(i -> request.lines().get(i).amount()).toList();
            BigDecimal base = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tax = Money.usd(base.multiply(rate.percent()).movePointLeft(2));
            List<BigDecimal> allocated = tax.signum() == 0 || base.signum() == 0
                ? weights.stream().map(w -> Money.usd(BigDecimal.ZERO)).toList()
                : Money.allocate(tax, weights, Money.USD_SCALE);
            List<BigDecimal> shares = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                shares.add(Money.usd(BigDecimal.ZERO));
            }
            for (int k = 0; k < lines.size(); k++) {
                int i = lines.get(k);
                shares.set(i, allocated.get(k));
                lineTax[i] = lineTax[i].add(allocated.get(k));
            }
            taxes.add(new JurisdictionTax(jurisdiction, Money.usd(base), rate.percent(), rate.from(), tax,
                List.copyOf(shares)));
        });
        List<LineResult> results = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (kinds[i] != null) {
                results.add(new LineResult(i, codes[i], kinds[i], reasons[i], certificates[i], lineTax[i]));
            }
        }
        BigDecimal total = taxes.stream().map(JurisdictionTax::tax).reduce(Money.usd(BigDecimal.ZERO), BigDecimal::add);
        return new Result(List.copyOf(results), List.copyOf(taxes), total, List.copyOf(problems));
    }

    private static Certificate certificate(Request request, String state) {
        if (state == null) {
            return null;
        }
        return request.certificates().stream()
            .filter(c -> state.equals(c.state()) && c.validOn(request.date()))
            .findFirst().orElse(null);
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                params.put((String) pairs[i], pairs[i + 1] instanceof LocalDate d ? d.toString() : pairs[i + 1]);
            }
        }
        return params;
    }
}
