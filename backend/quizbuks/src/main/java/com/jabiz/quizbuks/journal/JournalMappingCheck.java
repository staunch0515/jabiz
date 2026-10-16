package com.jabiz.quizbuks.journal;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the account mapping complete (Q9d, D-Q9d-1), at startup and in {@code platformCheck} (category
 * {@code QUIZBUKS}): every account of the chart ({@link QbLedger#ACCOUNTS}) has its mapping parameter in
 * {@link QbParams} - declared, controlled and in {@link QbParams#JOURNAL_ACCOUNTS} - and the mapping names no
 * account the chart does not have. A new account without one would stop every export moving it; whether a value is
 * set is checked by the export itself.
 */
@Component
class JournalMappingCheck implements PlatformCheck {

    @Override
    public List<CheckProblem> check() {
        return problems(QbLedger.ACCOUNTS.stream().map(QbLedger.Account::code).toList(),
            QbParams.ALL.stream().map(QbParams.Param::key).collect(Collectors.toSet()),
            Set.copyOf(QbParams.CONTROLLED), QbParams.JOURNAL_ACCOUNTS);
    }

    static List<CheckProblem> problems(List<String> accounts, Set<String> declared, Set<String> controlled,
        List<String> mapping) {
        List<CheckProblem> problems = new ArrayList<>();
        for (String account : accounts) {
            String key = QbParams.JOURNAL_ACCOUNT_PREFIX + account;
            if (!mapping.contains(key)) {
                problems.add(problem(key, "Account " + account + " has no mapping parameter in "
                    + "QbParams.JOURNAL_ACCOUNTS: the journal export could not post it"));
            }
            if (!declared.contains(key)) {
                problems.add(problem(key, "The mapping parameter of account " + account + " is not in QbParams.ALL: "
                    + "QB_SETUP would not create it"));
            }
            if (!controlled.contains(key)) {
                problems.add(problem(key, "The mapping parameter of account " + account + " is not controlled: it "
                    + "must change with four eyes only"));
            }
        }
        for (String key : mapping) {
            if (!key.startsWith(QbParams.JOURNAL_ACCOUNT_PREFIX)
                || !accounts.contains(key.substring(QbParams.JOURNAL_ACCOUNT_PREFIX.length()))) {
                problems.add(problem(key, "The mapping parameter names no account of the chart (QbLedger.ACCOUNTS)"));
            }
        }
        return problems;
    }

    private static CheckProblem problem(String location, String description) {
        return CheckProblem.error(CountryCatalog.CATEGORY, location, description);
    }
}
