package com.jabiz.quizbuks.help;

import java.util.List;
import java.util.Map;

/**
 * The pages the AI help may be told the user is on (F-C10: "the current page" is a name of this list only): each
 * entry's names and what each is, for the prompt. A name not on its entry's list is dropped, never sent; the play pages
 * ({@code /play/…}, an attempt's result) have no name and no help. The front ends turn their paths into these names
 * ({@code help/pages.ts}); a page renamed there is renamed here too.
 */
public final class HelpPages {

    /** The Takers' app: name, then what the page is (English, for the prompt). */
    static final Map<String, String> APP = ordered(
        "home", "the home feed of publications",
        "shorts", "the vertical short videos",
        "publication", "a publication's page before answering it",
        "history", "the list of quizzes the Taker answered",
        "wallet", "the Kudos wallet, its transactions and withdrawals",
        "inbox", "the messages",
        "conversations", "the conversations with Sponsors",
        "referrals", "referring friends",
        "certificates", "the Taker's certificates",
        "account", "the account menu",
        "profile", "the profile and settings",
        "help", "the help centre",
        "redeem", "collecting a prize at a Sponsor's place",
        "my-sponsor", "the Taker's own Sponsor figures in the app");

    /** The Sponsors' pages. */
    static final Map<String, String> SPONSOR = ordered(
        "dashboard", "the dashboard",
        "quizzes", "the list of quizzes",
        "quiz-edit", "editing a quiz (materials and questions)",
        "publications", "the list of publications",
        "publication-edit", "the publication wizard",
        "publication-detail", "a publication's page",
        "ai", "AI question writing and translation",
        "analytics", "the analytics",
        "assets", "the asset library",
        "shared-videos", "the shared videos",
        "video-uses", "the ledger of shared videos' uses",
        "benefits", "sponsored benefits",
        "conversations", "the conversations with Takers",
        "notices", "notices to Takers",
        "invitations", "the invitation list",
        "page", "the public Sponsor page",
        "products", "the products",
        "tree", "the knowledge map",
        "topics", "the topics",
        "wallet", "the wallet and top-ups",
        "transactions", "the transactions and invoices",
        "profile", "the Sponsor profile",
        "account", "the account",
        "help", "the help centre");

    private HelpPages() {}

    /** The page's name when it is on the entry's list; null otherwise (dropped, not refused). */
    public static String allowed(String entry, String page) {
        if (page == null) {
            return null;
        }
        Map<String, String> pages = QbHelp.SPONSOR.equals(entry) ? SPONSOR : QbHelp.APP.equals(entry) ? APP : Map.of();
        return pages.containsKey(page) ? page : null;
    }

    /** What the page is, for the prompt; null for a name not on the entry's list. */
    public static String describe(String entry, String page) {
        String name = allowed(entry, page);
        return name == null ? null : (QbHelp.SPONSOR.equals(entry) ? SPONSOR : APP).get(name);
    }

    /** The entry's names, in order. */
    public static List<String> names(String entry) {
        return List.copyOf((QbHelp.SPONSOR.equals(entry) ? SPONSOR : APP).keySet());
    }

    private static Map<String, String> ordered(String... pairs) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
