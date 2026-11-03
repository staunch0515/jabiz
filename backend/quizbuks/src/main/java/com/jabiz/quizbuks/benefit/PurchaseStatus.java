package com.jabiz.quizbuks.benefit;

import java.util.List;

/**
 * The states of a purchase (Q89, D-Q89-5), and what each act may start from. The sponsor writes and sends it, the
 * provider confirms or sends it back; any change after a confirmation asks the provider again. Q89b's allocations add
 * that a purchase publications draw on is not cancelled and keeps at least what they use; Q89d suspends and closes.
 */
public final class PurchaseStatus {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_PROVIDER = "PENDING_PROVIDER";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELLED = "CANCELLED";

    public static final List<String> ALL = List.of(DRAFT, PENDING_PROVIDER, CONFIRMED, SUSPENDED, CLOSED, CANCELLED);

    /** The sponsor changes these; a change of a confirmed one goes back to the provider. */
    public static final List<String> EDITABLE = List.of(DRAFT, PENDING_PROVIDER, CONFIRMED);

    private PurchaseStatus() {}

    public static boolean editable(String status) {
        return EDITABLE.contains(status);
    }

    /** The status after the sponsor saved a change: a draft stays one, the others wait for the provider. */
    public static String afterSave(String status) {
        return DRAFT.equals(status) ? DRAFT : PENDING_PROVIDER;
    }

    public static boolean submittable(String status) {
        return DRAFT.equals(status);
    }

    /** The provider answers a purchase waiting for it. */
    public static boolean answerable(String status) {
        return PENDING_PROVIDER.equals(status);
    }

    public static boolean cancellable(String status) {
        return EDITABLE.contains(status);
    }
}
