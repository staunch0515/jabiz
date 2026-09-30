package com.jabiz.runtime.sod;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * The actor holds both groups of permissions of a segregation-of-duties rule and runs a process that needs one of
 * them (docs/design/18-numbering-approvals-tasks.md section 4.3); answered with 403 {@code SOD_CONFLICT}.
 */
public class SodConflictException extends PermissionDeniedException {

    private final String ruleCode;

    public SodConflictException(String permission, String ruleCode, String message) {
        super(permission, message);
        this.ruleCode = ruleCode;
    }

    public String ruleCode() {
        return ruleCode;
    }
}
