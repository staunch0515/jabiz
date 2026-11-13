package com.jabiz.quizbuks.messaging;

import com.jabiz.mail.MailTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C8 (platform decision D72): the texts of every QuizBuks mail can be changed online, and the administrator who does
 * it sees the mail by its name and previews it with example values. So each template has its name in the three
 * languages ({@code mail.<name>.name}) and an example value for every parameter ({@code .sample}); a template added
 * later without them fails here, not on an administrator's screen. The templates that ask for a payment or speak of a
 * payout account or a payment method, and carry no token, are declared protected: their changes another administrator
 * publishes.
 */
class MailTemplatesCoverageTest {

    /**
     * The mail that asks the recipient to pay or transfer, or speaks of the account money is received on or the way it
     * is paid (a changed text could lead a taker or a sponsor to pay or transfer to the wrong place), without a token
     * to make it protected by itself. The rule for every template, old and new: anything that asks for a payment,
     * names a payout account or a payment method is protected. Confirmations that name neither ({@code qb.topup.paid},
     * {@code qb.topup.granted}, {@code qb.fee-credit.expiring}) stay ordinary notices.
     */
    private static final List<String> PROTECTED = List.of("qb.bank-account.changed", "qb.transfer.sent",
        "qb.transfer.failed", "qb.payout.action-needed", "qb.fee.invoice-issued", "qb.fee.invoice-overdue",
        "qb.topup.paid-cash", "qb.publication.funds-short", "qb.publication.exposure-low");

    private static List<MailTemplate> declared() throws ClassNotFoundException, IllegalAccessException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        List<MailTemplate> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com/jabiz/quizbuks")) {
            String className = definition.getBeanClassName();
            if (className.contains(".it.") || className.matches(".*(Test|IT|Support|Fixtures?)(\\$.*)?")) {
                continue; // the tests' own classes share the classpath
            }
            Class<?> type = Class.forName(className);
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == MailTemplate.class) {
                    field.setAccessible(true);
                    found.add((MailTemplate) field.get(null));
                }
            }
        }
        return found;
    }

    private static Properties messages(String language) throws IOException {
        Properties messages = new Properties();
        try (InputStream in = MailTemplatesCoverageTest.class.getResourceAsStream("/messages_" + language + ".properties")) {
            messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return messages;
    }

    @Test
    void everyTemplateHasItsNameInEveryLanguageAndAnExampleForEveryParameter() throws Exception {
        List<MailTemplate> templates = declared();
        assertThat(templates).as("the QuizBuks mail templates").hasSizeGreaterThanOrEqualTo(24);
        for (String language : List.of("en", "zh", "ja")) {
            Properties messages = messages(language);
            for (MailTemplate template : templates) {
                assertThat(messages.getProperty("mail." + template.name() + ".name"))
                    .as("mail." + template.name() + ".name in " + language).isNotBlank();
                assertThat(messages.getProperty("mail." + template.name() + ".subject")).isNotBlank();
                assertThat(messages.getProperty("mail." + template.name() + ".body")).isNotBlank();
            }
        }
        for (MailTemplate template : templates) {
            for (String param : template.params()) {
                assertThat(template.sampleOf(param)).as(template.name() + " example of " + param).isNotBlank();
            }
        }
    }

    @Test
    void theNoticesAboutPayingAndPayoutAccountsAreProtectedAndNothingElseIsExceptByItsToken() throws Exception {
        List<MailTemplate> templates = declared();
        assertThat(templates.stream().filter(MailTemplate::protectedText).map(MailTemplate::name).toList())
            .containsExactlyInAnyOrderElementsOf(PROTECTED);
    }
}
