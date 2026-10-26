package com.jabiz.quizbuks.publishing;

import java.util.List;

/**
 * How a digital prize reaches the winner (Q33, docs/quizbuks/plans/Q33-digital-prize.md, D-Q33-2): one code of a
 * pool the sponsor loaded in advance ({@link #POOL}, each winner gets a different one); one code or link every
 * winner gets ({@link #SHARED}); or the sponsor sends each prize by hand within {@code digitalSendDays}
 * ({@link #SPONSOR_SENT}). Frozen with the publication's policy, on the version and the card.
 */
public final class DigitalDelivery {

    public static final String POOL = "POOL";
    public static final String SHARED = "SHARED";
    public static final String SPONSOR_SENT = "SPONSOR_SENT";

    public static final List<String> ALL = List.of(POOL, SHARED, SPONSOR_SENT);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:digital-delivery";

    /** The delivery of a publication's values; null when it is not a digital prize or none is written. */
    public static String of(Object value) {
        return value == null || !ALL.contains(value.toString()) ? null : value.toString();
    }

    private DigitalDelivery() {}
}
