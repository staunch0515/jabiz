package com.jabiz.quizbuks.identity;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Warns at startup when mail is on but the sponsor console's address is unknown (neither {@code quizbuks.links.sponsor}
 * nor {@code jabiz.mail.base-url}): the onboarding mail would carry no link (D-Q2-7).
 */
@Component
class AccountLinksCheck implements PlatformCheck {

    private final boolean mailEnabled;
    private final AccountLinks links;

    AccountLinksCheck(@Value("${jabiz.mail.enabled:false}") boolean mailEnabled, AccountLinks links) {
        this.mailEnabled = mailEnabled;
        this.links = links;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(mailEnabled, links);
    }

    static List<CheckProblem> problems(boolean mailEnabled, AccountLinks links) {
        if (mailEnabled && links.sponsor().isEmpty()) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, "quizbuks.links.sponsor", "Mail is on but "
                + "the sponsor console's address is unknown: set quizbuks.links.sponsor or jabiz.mail.base-url, or "
                + "the onboarding mail carries no link"));
        }
        return List.of();
    }
}
