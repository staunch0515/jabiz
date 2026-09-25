package com.jabiz.dataset;

/**
 * Permission codes required to read and to write through a dataset (docs/design/03-dataset.md section 2.4).
 * A null code means "not declared", which is a startup error outside development.
 */
public record DatasetPermissions(String read, String write) {

    public static final DatasetPermissions UNDECLARED = new DatasetPermissions(null, null);

    public DatasetPermissions {
        read = blankToNull(read);
        write = blankToNull(write);
    }

    public boolean isDeclared() {
        return read != null && write != null;
    }

    private static String blankToNull(String code) {
        return code == null || code.isBlank() ? null : code;
    }
}
