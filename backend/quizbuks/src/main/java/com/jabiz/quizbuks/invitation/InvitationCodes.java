package com.jabiz.quizbuks.invitation;

import java.util.List;

/** The invitation rules' violation codes (Q25); their messages are in the three languages. */
public final class InvitationCodes {

    /** The taker is not on the publication's list (or the list names another account). */
    public static final String NOT_INVITED = "QB_NOT_INVITED";
    /** The publication is open to everybody: it has no invitation list. */
    public static final String AUDIENCE = "QB_INVITATION_AUDIENCE";
    /** The invitation was started or answered: it stays. */
    public static final String STATE = "QB_INVITATION_STATE";
    /** The list would exceed {@code quizbuks.invitations.max-per-publication}. */
    public static final String LIMIT = "QB_INVITATION_LIMIT";
    /** The sponsor's sends of the day would exceed {@code quizbuks.invitations.sends-per-day}. */
    public static final String QUOTA = "QB_INVITATION_QUOTA";
    /** Nothing to send: no invitation of the publication waits for its mail. */
    public static final String NONE = "QB_INVITATION_NONE";
    /** An address that is not one. */
    public static final String EMAIL = "QB_INVITATION_EMAIL";
    /** The publication is not live, so its invitations cannot be sent. */
    public static final String NOT_OPEN = "QB_INVITATION_NOT_OPEN";
    /** The sponsor has no verified mail server of their own (Q29): invitations leave through it only. */
    public static final String NO_MAIL_SERVER = "QB_INVITATION_NO_MAIL_SERVER";

    public static final List<String> ALL = List.of(NOT_INVITED, AUDIENCE, STATE, LIMIT, QUOTA, NONE, EMAIL, NOT_OPEN,
        NO_MAIL_SERVER);

    private InvitationCodes() {}
}
