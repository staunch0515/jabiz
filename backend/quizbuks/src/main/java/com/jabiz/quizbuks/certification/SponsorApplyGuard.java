package com.jabiz.quizbuks.certification;

import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Applying to become a sponsor from the app (Q69, D-Q69-7): a signed-in taker joins the sponsor entry only while they
 * hold the certification {@code SPONSOR_APPLY}. The app records their passes first ({@code QB_CERTIFICATION_CLAIM});
 * the guard reads the records. Registration at the sponsor console and its mailed join stay as they are in this step
 * (D-Q69-9): only the signed-in join is gated here. An operator's own sponsor (F-S1: its first lead's account, whose
 * approved onboarding the operator's creation wrote) joins without it: that is how the lead gets the sponsor role.
 */
@Component
class SponsorApplyGuard implements SignUpGuard {

    static final String SPONSOR_ENTRY = "sponsor";
    static final String RECORDS = "certificationRecords";
    static final String DIRECT_OF = "directSponsorOf";
    /** The refusal's code: its texts are {@code SIGN_UP_REFUSED.reason.<code>} in the three languages. */
    static final String REASON = "qb.certification-required";

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(RECORDS, QbCertifications.defaultDataset(QbCertifications.RECORD), "userId"),
            SignInLoad.of(DIRECT_OF, QbOperators.defaultDataset(QbOperators.OPERATOR), "directSponsorId"));
    }

    @Override
    public SignUpDecision check(SignUpAttempt attempt) {
        if (!SPONSOR_ENTRY.equals(attempt.entry()) || attempt.path() != SignUpAttempt.Path.SIGNED_IN_JOIN) {
            return SignUpDecision.ALLOW;
        }
        if (!attempt.rows(DIRECT_OF).isEmpty()) {
            return SignUpDecision.ALLOW;
        }
        List<CertificationHolding.Entry> entries = attempt.rows(RECORDS).stream()
            .filter(row -> Certifications.SPONSOR_APPLY.key().equals(row.get("certification")))
            .map(SponsorApplyGuard::entry).toList();
        return CertificationHolding.heldUntil(entries, Certifications.SPONSOR_APPLY, attempt.time()).isPresent()
            ? SignUpDecision.ALLOW : SignUpDecision.refuse(REASON);
    }

    private static CertificationHolding.Entry entry(Map<String, Object> row) {
        return new CertificationHolding.Entry(String.valueOf(row.get("kind")),
            CertificationProcesses.number(row.get("certificationVersion")),
            CertificationProcesses.instant(row.get("at")), CertificationProcesses.instant(row.get("validUntil")));
    }
}
