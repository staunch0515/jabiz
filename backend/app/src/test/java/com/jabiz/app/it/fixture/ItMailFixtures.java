package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailSenderSettings;
import com.jabiz.mail.MailSenderSource;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.security.secret.SealSecret;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
    /** A notification for the users of the portal only (decision D49): not among the administration's preferences. */
    public static final MailTemplate OFFERS = MailTemplate.define("it.offers", t -> t
        .category(MailCategory.NOTIFICATION).param("item").entries("portal"));
    /**
     * An invitation to a plain address with a sign-in link (decision D55): a notification, so the address may opt out
     * through its link.
     */
    public static final MailTemplate INVITE = MailTemplate.define("it.invite", t -> t
        .category(MailCategory.NOTIFICATION).param("item").entries("portal").signInLink(Duration.ofDays(7)));
    /** A mail that leaves through the application's own server (decision D56). */
    public static final MailTemplate VIA = MailTemplate.define("it.via", t -> t
        .category(MailCategory.TRANSACTIONAL).param("item"));

    /**
     * The application's mail servers (decision D56): a plain entity whose password is sealed by {@code SealSecret};
     * the source hands the platform only verified ones.
     */
    public static final EntityDefinition MAIL_SERVER = EntityDefinition.define("ItMailServer", eb -> {
        eb.physicalTable("it_mail_server");
        eb.primaryKey("key");
        eb.field("key", f -> f.physicalColumn("f_key").immutable(true).required(true).asText(64));
        eb.field("host", f -> f.physicalColumn("f_host").required(true).asText(253));
        eb.field("port", f -> f.physicalColumn("f_port").required(true).asNumeric(5, 0));
        eb.field("tls", f -> f.physicalColumn("f_tls").required(true).asText(10));
        eb.field("username", f -> f.physicalColumn("f_user").required(true).asText(200));
        eb.field("sealedPassword", f -> f.physicalColumn("f_secret").sensitive().asText(2000));
        eb.field("fromAddress", f -> f.physicalColumn("f_from").required(true).asText(320));
        eb.field("fromName", f -> f.physicalColumn("f_from_name").asText(100));
        eb.field("verified", f -> f.physicalColumn("f_verified").asBool());
        eb.field("rowVersion", f -> f.physicalColumn("f_version").asVersion());
    });

    public static final MailSenderSource MAIL_SERVERS = MailSenderSource.of("ItMailServer", "key", row ->
        Boolean.TRUE.equals(row.get("verified"))
            ? Optional.of(new MailSenderSettings((String) row.get("host"), ((Number) row.get("port")).intValue(),
                MailSenderSettings.Tls.valueOf((String) row.get("tls")), (String) row.get("username"),
                (String) row.get("sealedPassword"), (String) row.get("fromAddress"), (String) row.get("fromName")))
            : Optional.empty());

    /**
     * @param template {@code verify}, {@code reset}, {@code news} or {@code offers}
     * @param userId   the recipient user, or null for {@code address}
     * @param locale   the language to send in; null: the user's
     * @param value    the template's parameter
     * @param fail     whether the process fails after the mail step
     * @param entry    {@code invite}: the entry its sign-in link signs in at
     * @param to       {@code invite}: the path the link returns to
     * @param via      {@code via}: the key of the application's server the mail leaves through (decision D56)
     */
    public record SendInput(@NotBlank String template, String userId, String address, String locale, String value,
        Boolean fail, String entry, String to, String via) {

        public SendInput(String template, String userId, String address, String locale, String value, Boolean fail) {
            this(template, userId, address, locale, value, fail, null, null, null);
        }

        public SendInput(String template, String userId, String address, String locale, String value, Boolean fail,
            String entry, String to) {
            this(template, userId, address, locale, value, fail, entry, to, null);
        }
    }

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
                ItMailFixtures::recipient, Map.of("userName", ItMailFixtures::userName)))
            .step("Send the reset", SendMail.when(ctx -> "reset".equals(input(ctx).template()), RESET,
                ItMailFixtures::recipient, Map.of()))
            .step("Send the news", SendMail.when(ctx -> "news".equals(input(ctx).template()), NEWS,
                ItMailFixtures::recipient, Map.of("item", ctx -> input(ctx).value())))
            .step("Send the offers", SendMail.when(ctx -> "offers".equals(input(ctx).template()), OFFERS,
                ItMailFixtures::recipient, Map.of("item", ctx -> input(ctx).value())))
            .step("Send the invitation", SendMail.when(ctx -> "invite".equals(input(ctx).template()), INVITE,
                ItMailFixtures::recipient, Map.of("item", ctx -> input(ctx).value(),
                    MailTemplate.ENTRY_PARAM, ctx -> input(ctx).entry(), MailTemplate.RETURN_PARAM,
                    ctx -> input(ctx).to())))
            .step("Send through the shop's server", SendMail.<ProcessContext>via(ctx -> input(ctx).via())
                .when(ctx -> "via".equals(input(ctx).template()), VIA, ItMailFixtures::recipient,
                    Map.of("item", ctx -> input(ctx).value())))
            .compute("Fail on request", (metadata, ctx) -> {
                if (Boolean.TRUE.equals(input(ctx).fail())) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.INVALID_VALUE, "asked to fail"));
                }
            }));

    public static final ProcessDefinition<UseInput, Done, ProcessContext> USE = use("IT_MAIL_TOKEN_USE", "verify");
    public static final ProcessDefinition<UseInput, Done, ProcessContext> USE_RESET = use("IT_MAIL_RESET_USE",
        "reset");

    /** The settings of a server, with the password as given (sealed before it is stored). */
    public record ServerInput(@NotBlank String key, @NotBlank String host, int port, @NotBlank String tls,
        @NotBlank String username, @Sensitive String password, @NotBlank String fromAddress, String fromName,
        boolean verified) {
        @Override
        public String toString() {
            return "ServerInput[" + key + "]";
        }
    }

    public record ServerOutput(String key) {}

    private static final String SEALED = "sealed";
    private static final String SERVER_KEY = "serverKey";
    private static final String SERVER = "server";
    private static final String SERVER_DATASET = "urn:jabiz:dataset:it:ItMailServer";

    public static final ProcessDefinition<ServerInput, ServerOutput, ProcessContext> SERVER_SET =
        ProcessDefinition.define("IT_MAIL_SERVER_SET", 1, ServerInput.class, ServerOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ServerOutput(ctx.get(INPUT, ServerInput.class).key()))
                .compute("Name the server", (metadata, ctx) ->
                    ctx.put(SERVER_KEY, ctx.get(INPUT, ServerInput.class).key()))
                .step("Load the server as it is", LoadEntity.optional(SERVER_DATASET, SERVER_KEY, SERVER))
                .step("Seal the password", SealSecret.of(ctx -> ctx.get(INPUT, ServerInput.class).password(),
                    ctx -> ctx.get(INPUT, ServerInput.class).key(), SEALED))
                .compute("Record the server", (metadata, ctx) -> {
                    ServerInput input = ctx.get(INPUT, ServerInput.class);
                    // Corrected settings replace the row (the test fixes a refused server; the queued mail then goes
                    // out through it).
                    if (ctx.contains(SERVER)) {
                        EntityInstance current = ctx.get(SERVER, EntityInstance.class);
                        Map<String, Object> changes = new LinkedHashMap<>();
                        changes.put("host", input.host());
                        changes.put("port", input.port());
                        changes.put("tls", input.tls());
                        changes.put("username", input.username());
                        changes.put("sealedPassword", ctx.contains(SEALED) ? ctx.get(SEALED) : null);
                        changes.put("fromAddress", input.fromAddress());
                        changes.put("fromName", input.fromName());
                        changes.put("verified", input.verified());
                        ctx.changes().update("ItMailServer", current.id(), current.version(), changes);
                        return;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", input.key());
                    row.put("host", input.host());
                    row.put("port", input.port());
                    row.put("tls", input.tls());
                    row.put("username", input.username());
                    row.put("sealedPassword", ctx.contains(SEALED) ? ctx.get(SEALED) : null);
                    row.put("fromAddress", input.fromAddress());
                    row.put("fromName", input.fromName());
                    row.put("verified", input.verified());
                    ctx.changes().insert("ItMailServer", row);
                }));


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

    /** A secret by its declaration, which no mail may carry. */
    public record Secret(@Sensitive String pin) {
        @Override
        public String toString() {
            return "Secret[***]";
        }
    }

    /** The value; {@code secret:…} passes a {@link Secret} instead. */
    private static Object userName(ProcessContext ctx) {
        String value = input(ctx).value();
        return value != null && value.startsWith("secret:") ? new Secret(value) : value;
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
        MailTemplate itOffersMail() {
            return OFFERS;
        }

        @Bean
        MailTemplate itInviteMail() {
            return INVITE;
        }

        @Bean
        ProcessDefinition<SendInput, Done, ProcessContext> itMailSend() {
            return SEND;
        }

        @Bean
        MailTemplate itViaMail() {
            return VIA;
        }

        @Bean
        EntityDefinition itMailServerEntity() {
            return MAIL_SERVER;
        }

        @Bean
        MailSenderSource itMailServers() {
            return MAIL_SERVERS;
        }

        @Bean
        DatasetDefinition itMailServerDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define("urn:jabiz:dataset:it:ItMailServer", d -> d
                .targetEntityType("ItMailServer").asDefault().permissions(PERMISSION, PERMISSION)
                .policy(p -> p.processOnlyWrites()).storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        ProcessDefinition<ServerInput, ServerOutput, ProcessContext> itMailServerSet() {
            return SERVER_SET;
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
