package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.List;

/** Startup check of the single-page application configuration (category {@code WEB}, {@link JabizWebProperties}). */
@Component
class SpaConfigCheck implements PlatformCheck {

    static final String CATEGORY = "WEB";

    private final JabizWebProperties properties;

    SpaConfigCheck(JabizWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<CheckProblem> check() {
        return properties.problems().stream().map(text -> CheckProblem.error(CATEGORY, text)).toList();
    }
}
