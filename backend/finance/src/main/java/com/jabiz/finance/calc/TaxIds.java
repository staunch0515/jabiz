package com.jabiz.finance.calc;

import java.util.Optional;

/**
 * US taxpayer identification numbers (FIN-AP-002; IRS Form W-9): an employer identification number (EIN) of a
 * business, or the social security number (SSN) or individual taxpayer identification number (ITIN) of a person.
 * Whatever the punctuation they are entered with, they are kept in the form people write them, {@code 12-3456789} for an
 * EIN and {@code 123-45-6789} for an SSN or ITIN, which is also what the platform's {@code TAX_ID} mask expects. Pure.
 */
public final class TaxIds {

    public static final String SSN = "SSN";
    public static final String EIN = "EIN";
    public static final String ITIN = "ITIN";

    private TaxIds() {}

    /**
     * The number in its written form, or empty when it is not one of the type: nine digits (hyphens and spaces
     * ignored); an SSN never starts with 9, 000 or 666 and has no zero group; an ITIN starts with 9; an EIN does not
     * start with 00.
     */
    public static Optional<String> normalize(String type, String value) {
        if (type == null || value == null) {
            return Optional.empty();
        }
        String digits = value.replace("-", "").replace(" ", "");
        if (!digits.matches("[0-9]{9}")) {
            return Optional.empty();
        }
        String area = digits.substring(0, 3);
        String group = digits.substring(3, 5);
        String serial = digits.substring(5);
        return switch (type) {
            case EIN -> digits.startsWith("00") ? Optional.empty()
                : Optional.of(digits.substring(0, 2) + "-" + digits.substring(2));
            case SSN -> digits.startsWith("9") || area.equals("000") || area.equals("666") || group.equals("00")
                || serial.equals("0000") ? Optional.empty() : Optional.of(area + "-" + group + "-" + serial);
            case ITIN -> digits.startsWith("9") ? Optional.of(area + "-" + group + "-" + serial) : Optional.empty();
            default -> Optional.empty();
        };
    }
}
