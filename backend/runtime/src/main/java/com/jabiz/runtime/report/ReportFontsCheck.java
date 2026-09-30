package com.jabiz.runtime.report;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup self-check (docs/design/19-reports.md section 4): every font file of {@code jabiz.reports.pdf.fonts} can be
 * read. A wrong path would otherwise fail every PDF export, and only when one is asked for.
 */
@Component
public class ReportFontsCheck implements PlatformCheck {

    private final ReportSettings settings;

    public ReportFontsCheck(ReportSettings settings) {
        this.settings = settings;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (String path : settings.fontPaths()) {
            if (!Files.isReadable(Path.of(path))) {
                problems.add(CheckProblem.error("REPORTS", "jabiz.reports.pdf.fonts",
                    "cannot read the font file " + path));
            }
        }
        return problems;
    }
}
