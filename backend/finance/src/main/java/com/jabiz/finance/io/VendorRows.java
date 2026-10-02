package com.jabiz.finance.io;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a vendor file's free-text columns read (FIN-DI-001; ROADMAP F4a): the sample company's {@code vendors.csv}
 * gives the entity type in words ("single-member LLC", "municipal utility") and the 1099 setting as the form and box
 * people write ("1099-MISC box 1 (rents)"). Pure parsing; a text that does not read is the row's error.
 */
final class VendorRows {

    /** A 1099 setting: the form ({@code NEC} or {@code MISC}) and the box; both null when not reportable. */
    record Form1099(String form, String box) {}

    private static final Pattern FORM = Pattern.compile(
        "^(?:form\\s+)?1099-?(NEC|MISC)(?:\\s*,?\\s*box\\s*(\\d{1,2}))?(?:\\s*\\(.*\\))?$", Pattern.CASE_INSENSITIVE);

    private VendorRows() {}

    /** The entity type of {@code ApEntities.ENTITY_TYPE_VALUES} that the words name. */
    static String entityType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        if (value.contains("single member") || value.contains("disregarded")) {
            return "SINGLE_MEMBER_LLC";
        }
        if (value.contains("s corp")) {
            return "S_CORPORATION";
        }
        if (value.contains("c corp") || value.equals("corporation") || value.equals("inc")) {
            return "C_CORPORATION";
        }
        if (value.contains("partnership")) {
            return "PARTNERSHIP";
        }
        if (value.contains("individual") || value.contains("sole proprietor")) {
            return "INDIVIDUAL";
        }
        if (value.contains("trust") || value.contains("estate")) {
            return "TRUST_ESTATE";
        }
        if (value.contains("government") || value.contains("municipal") || value.contains("state agency")
            || value.contains("federal")) {
            return "GOVERNMENT";
        }
        if (value.contains("exempt") || value.contains("nonprofit") || value.contains("501(c)")) {
            return "TAX_EXEMPT";
        }
        String code = value.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (java.util.List.of("INDIVIDUAL", "SINGLE_MEMBER_LLC", "PARTNERSHIP", "C_CORPORATION", "S_CORPORATION",
            "TRUST_ESTATE", "GOVERNMENT", "TAX_EXEMPT", "OTHER").contains(code)) {
            return code;
        }
        throw new IllegalArgumentException("The entity type \"" + text.trim() + "\" is not one the system knows; "
            + "write it as \"C corporation\", \"partnership\", \"single-member LLC\", \"individual\" or similar");
    }

    /** The form and box of "1099-NEC box 1", "1099-MISC box 1 (rents)"; none for a blank text or "none". */
    static Form1099 form1099(String text) {
        if (text == null || text.isBlank() || text.trim().equalsIgnoreCase("none")) {
            return new Form1099(null, null);
        }
        Matcher m = FORM.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("The 1099 setting \"" + text.trim() + "\" does not read; write it as "
                + "\"1099-NEC box 1\" or \"1099-MISC box 1 (rents)\"");
        }
        return new Form1099(m.group(1).toUpperCase(Locale.ROOT), m.group(2) == null ? "1" : m.group(2));
    }

    /** "yes", "true", "y", "1" are yes; "no", "false", "n", "0" and blank are no. */
    static boolean yes(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (java.util.List.of("yes", "y", "true", "1").contains(value)) {
            return true;
        }
        if (java.util.List.of("no", "n", "false", "0").contains(value)) {
            return false;
        }
        throw new IllegalArgumentException("\"" + text.trim() + "\" is neither yes nor no");
    }
}
