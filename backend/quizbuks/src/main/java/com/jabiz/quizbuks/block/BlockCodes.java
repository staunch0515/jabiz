package com.jabiz.quizbuks.block;

import java.util.List;

/** The error codes of blocking (F-S8); each has its three-language message. */
public final class BlockCodes {

    /** A new start of a publication the caller blocked (its publication, quiz, sponsor or video provider). */
    public static final String BLOCKED = "QB_PUBLICATION_BLOCKED";
    /** The caller has {@code qb.block.max-per-user} blocks already. */
    public static final String LIMIT = "QB_BLOCK_LIMIT";
    /** A provider is blocked from a card with a shared video only. */
    public static final String NO_PROVIDER = "QB_BLOCK_NO_PROVIDER";
    /** One's own publication, oneself or one's own shared video is not blocked. */
    public static final String OWN = "QB_BLOCK_OWN";

    public static final List<String> ALL = List.of(BLOCKED, LIMIT, NO_PROVIDER, OWN);

    private BlockCodes() {}
}
