package com.jabiz.runtime.mail;

import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Platform step: puts the texts a template's mail is sent with in a language ({@link MailTexts#effective}: the
 * override administrators wrote, else the default; decision D72) into the context as a {@link MailTexts.Text}, for the
 * platform's processes that render mail in their own transaction ({@code TASK_NOTIFY}). Not for business processes,
 * which send mail with {@code SendMail}.
 */
@Component
public class LoadMailText<C extends ProcessContext> implements StepHandler<LoadMailText.Metadata<C>, C> {

    public record Metadata<C>(MailTemplate template, Function<C, Locale> locale, String targetKey) {
        public Metadata {
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(locale, "locale must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(MailTemplate template,
        Function<C, Locale> locale, String targetKey) {
        return StepSpec.of(LoadMailText.class, new Metadata<>(template, locale, targetKey));
    }

    private final MailTexts texts;

    LoadMailText(MailTexts texts) {
        this.texts = texts;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> texts.effective(metadata.template(), metadata.locale().apply(ctx))
            .doOnNext(text -> ctx.put(metadata.targetKey(), text))
            .then());
    }
}
