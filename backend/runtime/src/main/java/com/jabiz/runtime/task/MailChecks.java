package com.jabiz.runtime.task;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup self-check of e-mail (docs/design/18-numbering-approvals-tasks.md section 5.4): with
 * {@code jabiz.mail.enabled=true} a mail server ({@code spring.mail.host}) and a sender address
 * ({@code jabiz.mail.from}) must be configured, rather than notifications failing one by one.
 */
@Component
public class MailChecks implements PlatformCheck {

    public static final String CATEGORY = "MAIL";

    private final boolean enabled;
    private final String from;
    private final ObjectProvider<JavaMailSender> mail;

    public MailChecks(@Value("${jabiz.mail.enabled:false}") boolean enabled, @Value("${jabiz.mail.from:}") String from,
        ObjectProvider<JavaMailSender> mail) {
        this.enabled = enabled;
        this.from = from;
        this.mail = mail;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        if (from == null || from.isBlank()) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.mail.from", "mail is enabled but has no sender address"));
        }
        if (mail.getIfAvailable() == null) {
            problems.add(CheckProblem.error(CATEGORY, "spring.mail.host", "mail is enabled but no mail server is "
                + "configured"));
        }
        return problems;
    }
}
