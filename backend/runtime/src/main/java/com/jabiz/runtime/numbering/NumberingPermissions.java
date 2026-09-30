package com.jabiz.runtime.numbering;

/** Permissions of the numbering service (docs/design/18-numbering-approvals-tasks.md section 2). */
public final class NumberingPermissions {

    /** Reading the issued numbers (the NumberAssignment dataset), for completeness checks and audits. */
    public static final String READ = "numbering.read";
    /** Declared by the dataset; nobody writes through it, the step AssignNumber writes the numbers it issues. */
    public static final String WRITE = "numbering.write";

    private NumberingPermissions() {}
}
