package com.jabiz.quizbuks.messaging;

import com.jabiz.i18n.MessageCatalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes a message's texts in every language of the application when it is made (Q7-15): the messages
 * {@code qb.msg.<kind>.title} and {@code .body} with their {@code {name}} placeholders filled. So the inbox shows each
 * reader their own language, finds a message by its text in SQL, and a later change of the wording leaves the messages
 * already made as they were.
 */
public final class MessageTexts {

    private final MessageCatalog messages;

    public MessageTexts(MessageCatalog messages) {
        this.messages = messages;
    }

    /** A parameter's value in a language: most are the same in all (amounts, names), some are texts of their own. */
    public interface Value extends Function<Locale, String> {

        static Value of(Object value) {
            String text = value == null ? "" : String.valueOf(value);
            return locale -> text;
        }
    }

    /** A parameter that is itself a message, in each language. */
    public Value message(String code) {
        return locale -> find(code, locale);
    }

    /**
     * The message {@code code} in every supported language, by language code ({@code en}, {@code zh}, {@code ja}),
     * its placeholders filled with {@code params}.
     */
    public Map<String, String> render(String code, Map<String, Value> params) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (Locale locale : messages.supportedLocales()) {
            String text = find(code, locale);
            for (Map.Entry<String, Value> param : params.entrySet()) {
                text = text.replace("{" + param.getKey() + "}", param.getValue().apply(locale));
            }
            texts.put(locale.getLanguage(), text);
        }
        return texts;
    }

    private String find(String code, Locale locale) {
        return messages.find(code, locale).or(() -> messages.find(code, messages.defaultLocale()))
            .orElseThrow(() -> new IllegalStateException("No message " + code));
    }

    /** The codes of a kind of message's title and text. */
    public static String titleCode(String kind) {
        return "qb.msg." + kind + ".title";
    }

    public static String bodyCode(String kind) {
        return "qb.msg." + kind + ".body";
    }
}
