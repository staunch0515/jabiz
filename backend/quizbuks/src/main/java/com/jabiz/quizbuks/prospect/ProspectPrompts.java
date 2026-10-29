package com.jabiz.quizbuks.prospect;

import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What prospecting asks Claude (Q48c): fixed system prompts, and the request's data as JSON in a tag of its own, so
 * that nothing a direction's brief says reads as an instruction. The answer is one JSON object at the end of the
 * reply, checked by {@link ProspectAnswers}.
 */
final class ProspectPrompts {

    static final String FIND_SYSTEM = """
        You help a sales team find small and medium businesses that could use QuizBuks, a service where a business \
        publishes short quizzes about its own products and rewards the people who answer them. The best businesses \
        sell products that need explaining or have a story, sell to consumers, are neither a one-person stall nor a \
        national chain, and often run promotions.

        Search the web for businesses that match the brief in <request>. Each must have its own website. Leave out \
        marketplaces, directories, review and news sites, social network pages, and every domain listed under \
        excludedDomains. Do not contact anybody and do not open forms.

        End your reply with exactly one JSON object and nothing after it:
        {"candidates": [{"domain": "example.com", "url": "https://example.com/", "company": "Example Ltd"}]}
        Give at most perSearch candidates; give none rather than a doubtful one.""";

    static final String RESEARCH_SYSTEM = """
        You research one business's website for a sales team, so that a person can decide whether to write to it \
        about QuizBuks, a service where a business publishes short quizzes about its own products and rewards the \
        people who answer them.

        Read the site given in <request>: its home page, product pages and contact page (fetch only pages of that \
        site; search only to find them). Never invent anything: what the site does not show is null.

        Find: the company's name; its main products (at most five, each with a one-sentence description); the site's \
        language (an ISO 639-1 code) and country (ISO 3166-1 alpha-2); whether it sells online and whether the shop \
        runs on Shopify; the contact email address the site publishes for business enquiries (never a person's \
        private address, never one you guessed); and whether the site says it refuses advertising or promotional \
        email (refusesMarketing).

        Score from 0 to 100 how well the business fits QuizBuks: products that need explaining or have a story; \
        selling to consumers; neither a one-person stall nor a national chain; promotions; a site in Japanese, \
        Chinese or English; a Shopify shop. Give the reasons in short sentences in English.

        If the score is at least minScore, the site publishes a business address and does not refuse promotional \
        email, also write:
        - a trial quiz in the site's language (Japanese, Chinese or English; English for any other) about the \
          business's own products, from what the site says only: a title, a one-sentence intro, and exactly 5 \
          questions, each with exactly 4 options of which exactly one is correct. Be accurate and never disparaging;
        - a short email in the same language, written like a personal note: name one of their products, say in one \
          sentence what QuizBuks does for a business like theirs (if it is a Shopify shop, mention the QuizBuks app \
          for Shopify), and invite them to try the quiz made from their products. At most 120 characters of subject \
          and 1500 of body, plain text, no links, no greeting by a person's name you guessed, no signature (the \
          sender's details, the quiz link and the unsubscribe link are added after it).
        Otherwise trial, mailSubject and mailBody are null.

        End your reply with exactly one JSON object and nothing after it:
        {"company": "...", "language": "ja", "country": "JP", "sellsOnline": true, "shopify": false, \
        "publicEmail": "info@example.com", "refusesMarketing": false, "score": 80, "reasons": ["..."], \
        "products": [{"name": "...", "description": "..."}], \
        "trial": {"title": "...", "intro": "...", "questions": [{"stem": "...", "options": [{"text": "...", \
        "correct": true}, {"text": "...", "correct": false}, {"text": "...", "correct": false}, {"text": "...", \
        "correct": false}]}]}, "mailSubject": "...", "mailBody": "..."}""";

    /** The data of a search. */
    static String find(ProspectJobs.FindInput input, JsonMapper json) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("brief", input.brief());
        data.put("country", input.country());
        data.put("language", input.language());
        data.put("perSearch", input.perSearch());
        data.put("excludedDomains", input.excludedDomains());
        return "<request>" + escape(json.writeValueAsString(data)) + "</request>";
    }

    /**
     * The data of a research. The site's address is also written outside the tag, plainly: the provider fetches only
     * addresses that appear in the conversation's text.
     */
    static String research(ProspectJobs.ResearchInput input, JsonMapper json) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("site", input.url());
        data.put("domain", input.domain());
        data.put("brief", input.brief());
        data.put("preferredLanguage", input.language());
        data.put("minScore", input.minScore());
        return "<request>" + escape(json.writeValueAsString(data)) + "</request>\nThe site: " + input.url();
    }

    /** Keeps the data inside its tag: no {@code <} can open or close one. */
    static String escape(String value) {
        return value.replace("<", "\\u003c");
    }

    private ProspectPrompts() {}
}
