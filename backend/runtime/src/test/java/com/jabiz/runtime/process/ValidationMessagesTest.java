package com.jabiz.runtime.process;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation messages are interpolated on event loops (a process body, an import row). Hibernate Validator reads
 * its bundles through {@code ResourceBundle.getBundle}, whose cache holds them only softly: after a GC it reads the jar
 * again, a blocking read that BlockHound turns into a 500 instead of the 400. The platform's validator reads every
 * bundle when it is built, so interpolation never reads one, whatever language is asked for.
 */
class ValidationMessagesTest {

    private LocalValidatorFactoryBean validator;

    record Input(@NotNull String name, @Size(max = 2) String code, @Pattern(regexp = "[a-z]+") String word,
        @DecimalMax("10") BigDecimal amount) {}

    @BeforeEach
    void buildTheValidatorAsSpringBootDoes() {
        validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new MessageInterpolatorFactory().getObject());
        validator.setConfigurationInitializer(ValidationConfig.preloadedValidationMessages()::customize);
        validator.afterPropertiesSet();
    }

    @AfterEach
    void close() {
        validator.close();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void messagesAreInterpolatedWithoutReadingBundlesAfterTheJdkCacheIsCleared() {
        assertThat(validate()).hasSize(4);
        clearJdkBundleCache();

        for (Locale locale : List.of(Locale.ENGLISH, Locale.JAPANESE, Locale.SIMPLIFIED_CHINESE, Locale.KOREAN,
            Locale.GERMANY, Locale.ROOT, Locale.getDefault())) {
            List<String> messages = Mono.delay(Duration.ofMillis(1))
                .map(tick -> {
                    LocaleContextHolder.setLocale(locale);
                    try {
                        return validate().stream().map(ConstraintViolation::getMessage).toList();
                    } finally {
                        LocaleContextHolder.resetLocaleContext();
                    }
                })
                .block();
            assertThat(messages).as(locale.toString()).hasSize(4).allSatisfy(m -> assertThat(m).isNotBlank()
                .doesNotContain("{jakarta.validation"));
        }
    }

    @Test
    void japaneseIsAnsweredInJapanese() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        assertThat(validate().stream().filter(v -> v.getPropertyPath().toString().equals("name")).findFirst()
            .orElseThrow().getMessage()).isEqualTo("null は許可されていません");
    }

    /** A preloading interpolator rejects a language it has not loaded, so every other one is mapped to a loaded one. */
    @Test
    void otherLanguagesAreAnsweredInTheClosestPreloadedOne() {
        PreloadedMessageInterpolator interpolator =
            new PreloadedMessageInterpolator(List.of(Locale.CHINESE, Locale.JAPANESE), Locale.US);

        assertThat(interpolator.locales()).containsExactlyInAnyOrder(Locale.CHINESE, Locale.JAPANESE, Locale.US);
        assertThat(interpolator.preloaded(Locale.JAPANESE)).isEqualTo(Locale.JAPANESE);
        assertThat(interpolator.preloaded(Locale.JAPAN)).isEqualTo(Locale.JAPANESE);
        assertThat(interpolator.preloaded(Locale.SIMPLIFIED_CHINESE)).isEqualTo(Locale.CHINESE);
        assertThat(interpolator.preloaded(Locale.US)).isEqualTo(Locale.US);
        assertThat(interpolator.preloaded(Locale.UK)).isEqualTo(Locale.US);
        assertThat(interpolator.preloaded(Locale.KOREAN)).isEqualTo(Locale.US);
        assertThat(interpolator.preloaded(null)).isEqualTo(Locale.US);
    }

    private Set<ConstraintViolation<Input>> validate() {
        return validator.validate(new Input(null, "abc", "ABC", new BigDecimal("11")));
    }

    /** What a GC does to the bundles the JDK caches through soft references. */
    private static void clearJdkBundleCache() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ValidationMessagesTest.class.getClassLoader());
        loaders.add(HibernateValidator.class.getClassLoader());
        loaders.forEach(ResourceBundle::clearCache);
    }
}
