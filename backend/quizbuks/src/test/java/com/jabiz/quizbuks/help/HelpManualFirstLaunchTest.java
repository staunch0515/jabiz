package com.jabiz.quizbuks.help;

import static org.assertj.core.api.Assertions.assertThat;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.FirstLaunchSwitches;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The usage manuals the AI answers from (F-C10b) say what the first launch does with Kudos and entry fees (F-F10b):
 * Kudos are shown but not used, no publication offers a Kudos reward, no quiz takes an entry fee. While the
 * registry of the first launch's switches keeps those off, a manual that again says "rewards are paid in Kudos"
 * without the first launch's state fails here, not in an answer to a Sponsor or a Taker.
 */
class HelpManualFirstLaunchTest {

    /** The first-launch statement each manual makes, by entry and language. */
    private static final Map<String, Map<String, String>> FIRST_LAUNCH = Map.of(
        "sponsor", Map.of("zh", "首次上线时学分只显示、不使用", "ja", "運用開始時、クドスは表示のみで使用でき",
            "en", "At the first launch, Kudos are shown but not used"),
        "app", Map.of("zh", "首次上线时学分只显示、还不能使用", "ja", "運用開始時、クドスは表示のみで、まだ使えません",
            "en", "At the first launch, Kudos are shown but cannot be used yet"));

    private static final Map<String, Map<String, String>> NO_ENTRY_FEE = Map.of(
        "sponsor", Map.of("zh", "首次上线时不收报名费", "ja", "運用開始時、参加費はありません", "en", "At the first launch there are no entry fees"),
        "app", Map.of("zh", "首次上线时没有报名费", "ja", "運用開始時、参加費はありません", "en", "At the first launch there are no entry fees"));

    /** What the manuals said before F-F10b: Kudos rewards and entry fees as if they were open. */
    private static final List<String> OPEN_AS_IF = List.of("奖励可以用钱包中的学分支付", "推荐、报名费和语言",
        "報酬は、ウォレットのクドス", "紹介、参加費、言語", "Rewards are paid in Kudos", "Referrals, entry fees and languages",
        "有些需要报名费", "参加費がかかるクイズもあります。参加費は", "Some have an entry fee", "平台费 Kudos", "利用料クドス",
        "platform fee Kudos");

    @Test
    void theFirstLaunchKeepsKudosRewardsAndEntryFeesOff() {
        for (String key : List.of(QbParams.REWARD_KUDOS_ENABLED, QbParams.ENTRY_FEE_ENABLED, QbParams.KUDOS_BUY_ENABLED,
            QbParams.TRANSFER_ENABLED, QbParams.WITHDRAWAL_BANK_ENABLED)) {
            assertThat(FirstLaunchSwitches.row(key)).as(key).get().extracting(FirstLaunchSwitches.Row::value)
                .isEqualTo(false);
        }
    }

    @Test
    void everyManualStatesTheFirstLaunch() {
        FIRST_LAUNCH.forEach((entry, statements) -> {
            Map<String, String> manuals = HelpManualDrafts.of(entry);
            assertThat(manuals).as(entry).containsOnlyKeys(statements.keySet());
            statements.forEach((language, statement) -> {
                String manual = manuals.get(language);
                assertThat(manual).as(entry + "." + language).contains(statement, NO_ENTRY_FEE.get(entry).get(language));
                for (String claim : OPEN_AS_IF) {
                    assertThat(manual).as(entry + "." + language).doesNotContain(claim);
                }
            });
        });
    }
}
