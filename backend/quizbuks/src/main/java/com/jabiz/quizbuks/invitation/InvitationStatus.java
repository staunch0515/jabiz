package com.jabiz.quizbuks.invitation;

import java.util.List;

/**
 * Where an invitation stands (docs/quizbuks/plans/Q25-invitations.md): added by the sponsor, queued for the mail
 * job, sent, started by the invitee (their attempt is on it) and answered (with the attempt's score and reward).
 * Only the first three may be removed.
 */
public final class InvitationStatus {

    public static final String ADDED = "ADDED";
    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
    public static final String STARTED = "STARTED";
    public static final String ANSWERED = "ANSWERED";
    public static final List<String> ALL = List.of(ADDED, QUEUED, SENT, STARTED, ANSWERED);
    /** The statuses before the invitee answered anything: an invitation in one may still be removed. */
    public static final List<String> REMOVABLE = List.of(ADDED, QUEUED, SENT);

    public static boolean removable(Object status) {
        return REMOVABLE.contains(String.valueOf(status));
    }

    private InvitationStatus() {}
}
