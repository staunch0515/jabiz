package com.jabiz.runtime.imports;

import com.jabiz.runtime.ConcurrentUpdateException;

import java.util.Map;

/** 409: the file (or one of its references) has been imported already, possibly by a commit running at the same time. */
public class ImportConflictException extends ConcurrentUpdateException {

    private final String code;
    private final Map<String, Object> params;

    public ImportConflictException(String code, String message, Map<String, Object> params) {
        super(message);
        this.code = code;
        this.params = Map.copyOf(params);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }
}
