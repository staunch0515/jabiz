package com.jabiz.app.it.fixture;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Mail of the tests (docs/design/18-numbering-approvals-tasks.md section 5.6): a transactional template with a
 * one-time token, a notification, a process that sends either (and can fail after sending, to show that a rolled
 * back process sends nothing) and one that uses a token.
 */
public final class ItMailFixtures {

    public static final String PERMISSION = "it.mail";

    public static final MailTemplate VERIFY = MailTemplate.define("it.verify", t -> t
        .category(MailCategory.TRANSACTIONAL).param("userName").token("verify", Duration.ofHours(1)));
    public static final MailTemplate RESET = MailTemplate.define("it.reset", t -> t
        .category(MailCategory.TRANSACTIONAL).token("reset", Duration.ofHours(1)));
    public static final MailTemplate NEWS = MailTemplate.define("it.news", t -> t
        .category(MailCategory.NOTIFICATION).param("item"));

    /**
     * @param template {@code verify}, {@code reset} or {@code news}
     * @param userId   the recipient user, or null for {@code address}
     * @param locale   the language to send in; null: the user's
     * @param value    the template's parameter
     * @param fail     whether the process fails after the mail step
     */
    public record SendInput(@NotBlank String template, String userId, String address, String locale, String value,
        Boolean fail) {}

    public record UseInput(String token) {}

    public record Done(String userId, String address) {}

    private static final String INPUT = "input";
    private static final String USED = "used";

    public static final ProcessDefinition<SendInput, Done, ProcessContext> SEND =
        ProcessDefinition.define("IT_MAIL_SEND", 1, SendInput.class, Done.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new Done(input(ctx).userId(), input(ctx).address()))
            .step("Send the verification", SendMail.when(ctx -> "verify".equals(input(ctx).template()), VERIFY,
                ItMailFixtures::recipient, Map.of("userName", ctx -> input(ctx).value())))
            .step("Send the reset", SendMail.when(ctx -> "reset".equals(input(ctx).template()), RESET,
                ItMailFixtures::recipient, Map.of()))
            .step("Send the news", SendMail.when(ctx -> "news".equals(input(ctx).template()), NEWS,
                ItMailFixtures::recipient, Map.of("item", ctx -> input(ctx).value())))
            .compute("Fail on request", (metadata, ctx) -> {
                if (Boolean.TRUE.equals(input(ctx).fail())) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.INVALID_VALUE, "asked to fail"));
                }
            }));

    public static final ProcessDefinition<UseInput, Done, ProcessContext> USE = use("IT_MAIL_TOKEN_USE", "verify");
    public static final ProcessDefinition<UseInput, Done, ProcessContext> USE_RESET = use("IT_MAIL_RESET_USE",
        "reset");

    private static ProcessDefinition<UseInput, Done, ProcessContext> use(String name, String purpose) {
        return ProcessDefinition.define(name, 1, UseInput.class, Done.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> {
                MailTokens.Consumed used = ctx.get(USED, MailTokens.Consumed.class);
                return new Done(used.userId(), used.address());
            })
            .step("Use the token", MailTokens.consume(purpose, ctx -> ctx.get(INPUT, UseInput.class).token(),
                USED)));
    }

    private static SendInput input(ProcessContext ctx) {
        return ctx.get(INPUT, SendInput.class);
    }

    private static MailRecipient recipient(ProcessContext ctx) {
        SendInput input = input(ctx);
        Locale locale = input.locale() == null ? null : Locale.of(input.locale());
        return input.userId() != null ? MailRecipient.user(input.userId(), input.address(), locale)
            : MailRecipient.address(input.address(), locale);
    }

    private ItMailFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        MailTemplate itVerifyMail() {
            return VERIFY;
        }

        @Bean
        MailTemplate itResetMail() {
            return RESET;
        }

        @Bean
        MailTemplate itNewsMail() {
            return NEWS;
        }

        @Bean
        ProcessDefinition<SendInput, Done, ProcessContext> itMailSend() {
            return SEND;
        }

        @Bean
        ProcessDefinition<UseInput, Done, ProcessContext> itMailTokenUse() {
            return USE;
        }

        @Bean
        ProcessDefinition<UseInput, Done, ProcessContext> itMailResetUse() {
            return USE_RESET;
        }
    }
}
