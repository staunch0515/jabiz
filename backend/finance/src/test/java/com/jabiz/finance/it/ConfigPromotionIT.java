package com.jabiz.finance.it;

import com.jabiz.finance.config.ConfigEntities;
import com.jabiz.finance.config.ConfigPackage;
import com.jabiz.finance.config.ConfigProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.report.StatementEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.tax.TaxEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration promotion (FIN-SC-005; ROADMAP F10d). The books' configuration is exported and proposed back unchanged:
 * nothing to do, which shows the package carries the configuration whole. The test environment's package has, on top,
 * a new Texas rate from 2026-07-01, a Legal Fees account and a retitled income statement: proposed, it lists those
 * three changes; a hash not handed over with it and an account changing its type are refused; its proposer cannot
 * publish it, another controller does, and the rate, the account and the new layout version are there, with who
 * published them when, in the proposal and in the audit trail. A withdrawn proposal is not published.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConfigPromotionIT extends FinanceItSupport {

    @Test
    void aTestConfigurationIsPromotedUnderFourEyes() {
        openReceivablesHolding();
        String controller = inRoles("controller", FinanceRoles.CONTROLLER);
        String other = inRoles("controller-2", FinanceRoles.CONTROLLER);

        // A layout of 200 rows, the most a layout has, published three times: 600 rows, more than a dataset gives in
        // one read, all of them read.
        List<Map<String, Object>> headings = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            headings.add(Map.of("lineCode", "H" + i, "label", "Heading " + i, "kind", "HEADING"));
        }
        for (int version = 1; version <= 3; version++) {
            ok("FIN_STATEMENT_LAYOUT_PUBLISH", controller, Map.of("layoutCode", "BIG", "statement",
                "INCOME_STATEMENT", "title", "Long layout " + version, "rows", headings));
        }
        // The export: a file with its hash; the same configuration proposed back changes nothing.
        Map<String, Object> exported = ok(ConfigProcesses.EXPORT, controller, Map.of("source", "test"));
        String text = new String(get("/api/generated-files/" + exported.get("fileId"), controller).expectStatus()
            .isOk().expectBody(byte[].class).returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(ConfigPackage.sha256(text)).isEqualTo(exported.get("sha256"));
        ConfigPackage here = ConfigPackage.read(text);
        assertThat(here.source()).isEqualTo("test");
        assertThat(here.accounts()).hasSize(((Number) exported.get("accounts")).intValue()).isNotEmpty();
        assertThat(here.taxCodes()).extracting(ConfigPackage.TaxCode::taxCode).contains("TX-AUSTIN", "TX-RESALE");
        assertThat(here.layouts()).filteredOn(l -> "BIG".equals(l.layoutCode())).singleElement()
            .satisfies(l -> assertThat(l.rows()).hasSize(200).first().satisfies(r -> assertThat(r.lineCode())
                .isEqualTo("H0")))
            .satisfies(l -> assertThat(l.title()).isEqualTo("Long layout 3"));
        assertThat(refused(ConfigProcesses.PROPOSE, controller, Map.of("packageText", text, "sha256",
            exported.get("sha256")), 422)).isEqualTo(ConfigProcesses.NO_CHANGES);

        // The test environment's package: a new rate, a new account, a retitled income statement.
        ConfigPackage.Rate austin = here.rates().stream().filter(r -> r.ratePercent().signum() > 0).findFirst()
            .orElseThrow();
        List<ConfigPackage.Rate> rates = new ArrayList<>(here.rates());
        rates.add(new ConfigPackage.Rate(austin.jurisdictionCode(), LocalDate.parse("2026-07-01"),
            new BigDecimal("2.2500")));
        List<ConfigPackage.Account> accounts = new ArrayList<>(here.accounts());
        ConfigPackage.Account fees = here.accounts().stream().filter(a -> "6400".equals(a.accountCode())).findFirst()
            .orElseThrow();
        accounts.add(new ConfigPackage.Account("6450", "Legal Fees", fees.financialType(), fees.normalBalance(),
            fees.statementLine(), fees.cashFlowClass(), null, false, null, fees.parentCode(), false, true));
        List<ConfigPackage.Layout> layouts = here.layouts().stream().map(l -> "IS".equals(l.layoutCode())
            ? new ConfigPackage.Layout(l.layoutCode(), l.statement(), "Statement of operations", l.rows()) : l).toList();
        ConfigPackage test = new ConfigPackage(ConfigPackage.FORMAT, "test", Instant.parse("2026-01-30T15:00:00Z"),
            accounts, here.jurisdictions(), rates, here.taxCodes(), layouts, here.reportSettings());
        String testText = test.text();
        String testHash = ConfigPackage.sha256(testText);

        // A hash not handed over with the package, an account changing its type: refused.
        assertThat(refused(ConfigProcesses.PROPOSE, controller, Map.of("packageText", testText, "sha256",
            exported.get("sha256")), 422)).isEqualTo(ConfigProcesses.HASH_MISMATCH);
        List<ConfigPackage.Account> retyped = new ArrayList<>(accounts);
        retyped.replaceAll(a -> "6400".equals(a.accountCode()) ? new ConfigPackage.Account(a.accountCode(),
            a.accountName(), "ASSET", "DEBIT", a.statementLine(), null, null, false, null, a.parentCode(), false, true)
            : a);
        String retypedText = new ConfigPackage(ConfigPackage.FORMAT, "test", null, retyped, here.jurisdictions(), rates,
            here.taxCodes(), layouts, here.reportSettings()).text();
        assertThat(refused(ConfigProcesses.PROPOSE, controller, Map.of("packageText", retypedText, "sha256",
            ConfigPackage.sha256(retypedText)), 422)).isEqualTo(ConfigProcesses.INVALID);

        // Proposed: its three changes; pasted with Windows line ends, the same package.
        Map<String, Object> proposed = ok(ConfigProcesses.PROPOSE, controller, Map.of("packageText",
            testText + "\r\n", "sha256", testHash));
        assertThat(proposed).containsEntry("packageHash", testHash).containsEntry("source", "test");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> differences = (List<Map<String, Object>>) proposed.get("differences");
        assertThat(differences).extracting(d -> d.get("area") + " " + d.get("key") + " " + d.get("action"))
            .containsExactlyInAnyOrder("ACCOUNT 6450 NEW", "RATE " + austin.jurisdictionCode() + "@2026-07-01 NEW",
                "LAYOUT IS CHANGED");
        String importId = (String) proposed.get("importId");
        assertThat(read(ConfigEntities.IMPORT_DATASET, importId)).containsEntry("status", ConfigEntities.PROPOSED)
            .containsEntry("proposedBy", "controller").containsEntry("changes", 3);

        // Four eyes: not its proposer.
        assertThat(refused(ConfigProcesses.PUBLISH, controller, Map.of("importId", importId), 422))
            .isEqualTo(ConfigProcesses.SAME_PERSON);
        Map<String, Object> published = ok(ConfigProcesses.PUBLISH, other, Map.of("importId", importId));
        assertThat(published).containsEntry("status", ConfigEntities.PUBLISHED).containsEntry("publishedBy",
            "controller-2").containsEntry("changes", 3);

        // FIN-SC-005 acceptance 1: the rate is promoted, with its approver and time on record.
        assertThat(find(TaxEntities.RATE_DATASET, "jurisdictionCode", austin.jurisdictionCode()))
            .anySatisfy(r -> assertThat(r).containsEntry("effectiveFrom", "2026-07-01"));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "6450")).hasSize(1);
        assertThat(find(StatementEntities.LAYOUT_DATASET, "layoutCode", "IS"))
            .anySatisfy(l -> assertThat(l).containsEntry("title", "Statement of operations")
                .containsEntry("publishedBy", "controller-2"));
        Map<String, Object> record = read(ConfigEntities.IMPORT_DATASET, importId);
        assertThat(record).containsEntry("status", ConfigEntities.PUBLISHED).containsEntry("publishedBy",
            "controller-2").containsEntry("publishedAt", clock.instant().toString()).containsEntry("packageHash",
                testHash);
        assertThat(query("SELECT actor_id FROM sys_audit_record WHERE entity_type = ? AND action = 'INSERT'",
            TaxEntities.RATE)).anySatisfy(r -> assertThat(r).containsEntry("actor_id", "controller-2"));
        // Published, it is not published again; the environment now has what the package says.
        assertThat(refused(ConfigProcesses.PUBLISH, other, Map.of("importId", importId), 422))
            .isEqualTo(ConfigProcesses.NOT_PROPOSED);
        assertThat(refused(ConfigProcesses.PROPOSE, controller, Map.of("packageText", testText, "sha256", testHash),
            422)).isEqualTo(ConfigProcesses.NO_CHANGES);

        // A withdrawn proposal is not published; the original package now takes the changes back out, but keeps
        // what it cannot take away: the account and the rate are only here.
        Map<String, Object> back = ok(ConfigProcesses.PROPOSE, controller, Map.of("packageText", text, "sha256",
            exported.get("sha256")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backDifferences = (List<Map<String, Object>>) back.get("differences");
        assertThat(backDifferences).extracting(d -> d.get("area") + " " + d.get("key") + " " + d.get("action"))
            .containsExactlyInAnyOrder("ACCOUNT 6450 ONLY_HERE", "RATE " + austin.jurisdictionCode()
                + "@2026-07-01 ONLY_HERE", "LAYOUT IS CHANGED");
        ok(ConfigProcesses.WITHDRAW, other, Map.of("importId", back.get("importId")));
        assertThat(refused(ConfigProcesses.PUBLISH, other, Map.of("importId", back.get("importId")), 422))
            .isEqualTo(ConfigProcesses.NOT_PROPOSED);
        assertThat(refused(ConfigProcesses.PUBLISH, other, Map.of("importId", "not-an-id"), 422))
            .isEqualTo(ConfigProcesses.NOT_FOUND);
        // Neither the export nor the promotion without the permissions.
        run(ConfigProcesses.EXPORT, inRoles("clerk", FinanceRoles.ACCOUNTANT), Map.of("source", "test"))
            .expectStatus().isForbidden();
        assertOnlyInserted("fi_config_import_version");
    }
}
