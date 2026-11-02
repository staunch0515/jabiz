package com.jabiz.quizbuks.conversation;

/** The codes of the conversations' refusals (Q82); their texts are the messages of the same names. */
public final class ConversationCodes {

    /** The caller has not completed the publication, nor referred people to it, or it is no sponsor's. */
    public static final String NOT_ELIGIBLE = "QB_CONVERSATION_NOT_ELIGIBLE";
    /** The taker blocked the sponsor: nothing opens or is sent until they remove the block. */
    public static final String BLOCKED = "QB_CONVERSATION_BLOCKED";
    /** The conversation is closed: only the taker opens it again. */
    public static final String CLOSED = "QB_CONVERSATION_CLOSED";
    /** The text holds a web or mail address, a phone number or an account elsewhere. */
    public static final String ADDRESS = "QB_CONVERSATION_ADDRESS";
    /** The caller sent {@code qb.conversation.daily-cap} messages in this conversation in 24 hours. */
    public static final String DAILY_CAP = "QB_CONVERSATION_DAILY_CAP";
    /** The other side's account is banned or gone: the conversation takes no message. */
    public static final String UNAVAILABLE = "QB_CONVERSATION_UNAVAILABLE";
    /** An empty message. */
    public static final String BODY_REQUIRED = "QB_CONVERSATION_BODY_REQUIRED";
    /** A message longer than {@value ConversationRules#BODY_LENGTH} characters. */
    public static final String BODY_TOO_LONG = "QB_CONVERSATION_BODY_TOO_LONG";

    private ConversationCodes() {}
}
