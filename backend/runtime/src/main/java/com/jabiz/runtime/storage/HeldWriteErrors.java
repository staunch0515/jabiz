package com.jabiz.runtime.storage;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Errors of writes held back in a transaction (ROADMAP phase 16p-2, T9) that surfaced in a later step than the one that
 * made the write: they are reported alone, as the write's own step would have reported them, not together with the
 * violations the process collected after it (review of 16p-2, L3). Kept by identity and weakly: nothing outlives the
 * error.
 */
public final class HeldWriteErrors {

    private static final Set<Throwable> MARKED = Collections.synchronizedSet(
        Collections.newSetFromMap(new WeakHashMap<Throwable, Boolean>()));

    private HeldWriteErrors() {}

    /** Marks {@code error} as a held write's and returns it. */
    static <T extends Throwable> T mark(T error) {
        MARKED.add(error);
        return error;
    }

    /** Whether {@code error} is the error of a write held back in its transaction. */
    public static boolean isHeldWriteError(Throwable error) {
        return error != null && MARKED.contains(error);
    }
}
