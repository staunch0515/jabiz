package com.jabiz.runtime;

import com.jabiz.entity.Violation;

import java.util.List;

/** A request body larger than the entry point accepts (413); raised before the rest of the body is read. */
public class PayloadTooLargeException extends RuntimeException {

    private final Violation violation;

    public PayloadTooLargeException(Violation violation) {
        super(violation.message());
        this.violation = violation;
    }

    public List<Violation> violations() {
        return List.of(violation);
    }
}
