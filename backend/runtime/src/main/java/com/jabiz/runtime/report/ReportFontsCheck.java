package com.jabiz.runtime.report;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup self-check (docs/design/19-reports.md section 4): Noto Sans has no Chinese or Japanese characters, so an
 * application offering either language needs {@code jabiz.reports.pdf.fonts} for its PDF reports; without it their
 * texts come out as question marks. A warning: CSV and Excel exports are not affected.
 */
@Component
public class ReportFontsCheck implements PlatformCheck {

    private final List<String> languages;
    private final ReportSettings settings;

    public ReportFontsCheck(@Value("${jabiz.i18n.languages:zh,ja,en}") List<String> languages,
        ReportSettings settings) {
        this.languages = languages.stream().map(String::trim).toList();
        this.settings = settings;
    }

    @Override
    public List<CheckProblem> check() {
        List<String> needing = languages.stream().filter(l -> l.equals("zh") || l.equals("ja")).toList();
        if (needing.isEmpty() || settings.hasFallbackFonts()) {
            return List.of();
        }
        return List.of(CheckProblem.warning("REPORTS", "jabiz.reports.pdf.fonts", "PDF reports in " + needing
            + " need a font with their characters; without one they show question marks"));
    }
}
