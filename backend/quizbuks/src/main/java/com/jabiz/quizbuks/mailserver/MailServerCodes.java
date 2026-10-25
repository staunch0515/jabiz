package com.jabiz.quizbuks.mailserver;

import java.util.List;

/** The rule codes of a sponsor's mail server (Q29); their texts are the messages of the same name. */
public final class MailServerCodes {

    /** A setting the platform refuses (the detail says which and why): 400 on the field. */
    public static final String INVALID = "QB_MAIL_SERVER_INVALID";
    /** The first save needs the password. */
    public static final String PASSWORD = "QB_MAIL_SERVER_PASSWORD";
    /** The sponsor's account has no address the test mail could go to. */
    public static final String NO_ADDRESS = "QB_MAIL_SERVER_NO_ADDRESS";

    public static final List<String> ALL = List.of(INVALID, PASSWORD, NO_ADDRESS);

    private MailServerCodes() {}
}
