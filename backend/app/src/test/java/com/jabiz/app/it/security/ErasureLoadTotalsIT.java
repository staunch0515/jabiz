package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.ItErasureFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.erasure.AccountDeletionChecks;
import com.jabiz.runtime.erasure.ErasureNotices;
import com.jabiz.runtime.erasure.PersonalDataErasers;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loads of the notices before an account is deleted that read a count or a total, or with fixed conditions
 * (ROADMAP 17zz22; docs/design/10-security.md section 17.3): a user with more rows than a rows load may read gets
 * them counted and added up, one statement a load, within the dataset's scope; a rows load over the limit is still an
 * error; the startup check refuses what would tell a hidden value or never read.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class ErasureLoadTotalsIT extends SecurityItSupport {

    static final String ALL = "all";
    static final String OPEN = "open";
    static final String LIVE = "live";
    static final String OPEN_AMOUNT = "openAmount";
    static final String VOID_AMOUNT = "voidAmount";
    static final String EAST = "east";
    static final String CUSTOMERS = "customers";

    @Autowired
    DatasetEntityManager entities;
    @Autowired
    DatasetRegistry datasets;
    @Autowired
    EntityDefinitionRegistry definitions;
    @Autowired
    MessageCatalog messages;
    @Autowired
    SignInEntries entries;

    /** An eraser with these loads; what its notices were given is kept in {@code seen}. */
    static PersonalDataEraser eraser(List<ErasureLoad> loads, AtomicReference<ErasureSubject> seen) {
        return new PersonalDataEraser() {
            @Override
            public List<ErasedEntity> entities() {
                return List.of();
            }

            @Override
            public List<ErasureLoad> loads() {
                return loads;
            }

            @Override
            public List<ErasureNotice> notices(ErasureSubject subject) {
                seen.set(subject);
                return subject.counts().getOrDefault(OPEN, 0L) > 0
                    ? List.of(ErasureNotice.of("it.open-points", Map.of("count", subject.count(OPEN)))) : List.of();
            }
        };
    }

    static PersonalDataErasers erasers(PersonalDataEraser... erasers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < erasers.length; i++) {
            beans.addBean("eraser" + i, erasers[i]);
        }
        return new PersonalDataErasers(beans.getBeanProvider(PersonalDataEraser.class));
    }

    List<ErasureNotice> notices(PersonalDataErasers erasers, String userId) {
        RequestContext context = new RequestContext("it-erasure", null, Locale.ENGLISH, userId, Set.of(), Set.of());
        return new ErasureNotices(erasers, entities, datasets, definitions, messages).of(userId)
            .contextWrite(view -> RequestContexts.put(Context.empty(), context)).block();
    }

    /**
     * 150 points of the user: 100 OPEN of 10 yen in the east, 30 USED of 5 yen in the west, 20 VOID of 1 yen in the
     * west; and 7 OPEN points of someone else.
     */
    static String points() {
        String user = UUID.randomUUID().toString();
        execute("INSERT INTO it_erasure_point (f_id, f_user, f_status, f_region, f_amount) SELECT ? || '-' || g, ?, "
            + "CASE WHEN g <= 100 THEN 'OPEN' WHEN g <= 130 THEN 'USED' ELSE 'VOID' END, "
            + "CASE WHEN g <= 100 THEN 'EAST' ELSE 'WEST' END, "
            + "CASE WHEN g <= 100 THEN 10 WHEN g <= 130 THEN 5 ELSE 1 END FROM generate_series(1, 150) g", user, user);
        String other = UUID.randomUUID().toString();
        execute("INSERT INTO it_erasure_point (f_id, f_user, f_status, f_region, f_amount) SELECT ? || '-' || g, ?, "
            + "'OPEN', 'EAST', 1000 FROM generate_series(1, 7) g", other, other);
        return user;
    }

    @Test
    void countsAndTotalsReadOneStatementALoadHoweverManyRows() {
        String user = points();
        AtomicReference<ErasureSubject> seen = new AtomicReference<>();
        PersonalDataErasers erasers = erasers(eraser(List.of(
            ErasureLoad.count(ALL, ItErasureFixtures.DATASET, "userId"),
            ErasureLoad.count(OPEN, ItErasureFixtures.DATASET, "userId").where("status", "OPEN"),
            ErasureLoad.count(LIVE, ItErasureFixtures.DATASET, "userId").where("status", "OPEN", "USED"),
            ErasureLoad.sum(OPEN_AMOUNT, ItErasureFixtures.DATASET, "userId", "amount").where("status", "OPEN"),
            // Two conditions, both to hold: nothing is VOID in the east.
            ErasureLoad.sum(VOID_AMOUNT, ItErasureFixtures.DATASET, "userId", "amount").where("status", "VOID")
                .where("region", "EAST")), seen));
        SqlStatementLog.clear();
        List<ErasureNotice> found = notices(erasers, user);
        List<String> statements = List.copyOf(SqlStatementLog.STATEMENTS);
        assertThat(found).singleElement().satisfies(notice -> {
            assertThat(notice.code()).isEqualTo("it.open-points");
            assertThat(notice.params()).containsEntry("count", 100L);
        });
        ErasureSubject subject = seen.get();
        assertThat(subject.count(ALL)).isEqualTo(150);
        assertThat(subject.count(OPEN)).isEqualTo(100);
        assertThat(subject.count(LIVE)).isEqualTo(130);
        assertThat(subject.sum(OPEN_AMOUNT)).isEqualByComparingTo("1000");
        assertThat(subject.sum(VOID_AMOUNT)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(subject.data()).isEmpty();
        // One statement a load, whatever the number of rows: three counts and two totals.
        assertThat(statements).filteredOn(sql -> sql.contains("it_erasure_point")).hasSize(5);
        assertThat(statements).filteredOn(sql -> sql.contains("count(*)")).hasSize(3);
        assertThat(statements).filteredOn(sql -> sql.contains("sum(f_amount)")).hasSize(2);
        // A misspelt name is an error, not 0.
        assertThatThrownBy(() -> subject.count("opne")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subject.sum(ALL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDatasetsScopeApplies() {
        String user = points();
        AtomicReference<ErasureSubject> seen = new AtomicReference<>();
        notices(erasers(eraser(List.of(ErasureLoad.count(EAST, ItErasureFixtures.EAST_DATASET, "userId"),
            ErasureLoad.sum(VOID_AMOUNT, ItErasureFixtures.EAST_DATASET, "userId", "amount").where("status", "VOID")),
            seen)), user);
        assertThat(seen.get().count(EAST)).isEqualTo(100);
        assertThat(seen.get().sum(VOID_AMOUNT)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aRowsLoadStillReadsAtMostAHundredAndAConditionNarrowsIt() {
        String user = points();
        AtomicReference<ErasureSubject> seen = new AtomicReference<>();
        // 150 rows: an error, never cut short (as before).
        assertThatThrownBy(() -> notices(erasers(eraser(List.of(ErasureLoad.of(ALL, ItErasureFixtures.DATASET,
            "userId")), seen)), user)).hasMessageContaining("found more than " + ErasureSubject.MAX_ROWS + " rows");
        // With a condition the same load reads the 30 USED ones; a temporal entity's count reads its current versions.
        notices(erasers(eraser(List.of(ErasureLoad.of(LIVE, ItErasureFixtures.DATASET, "userId").where("status", "USED"),
            ErasureLoad.count(CUSTOMERS, CommerceEntities.CUSTOMER_DATASET, "userId")), seen)), user);
        assertThat(seen.get().rows(LIVE)).hasSize(30).allSatisfy(row -> assertThat(row).containsEntry("status", "USED"));
        assertThat(seen.get().count(CUSTOMERS)).isZero();
    }

    @Test
    void theStartupCheckRefusesWhatWouldTellAHiddenValueOrNeverRead() {
        PersonalDataEraser broken = eraser(List.of(
            ErasureLoad.count("a", ItErasureFixtures.DATASET, "userId").where("nothing", "x"),
            ErasureLoad.count("b", ItErasureFixtures.DATASET, "userId").where("secret", "x"),
            ErasureLoad.count("c", ItErasureFixtures.DATASET, "userId").where("card", "1234"),
            ErasureLoad.count("d", ItErasureFixtures.DATASET, "userId").where("status", "OPEN", "GONE"),
            ErasureLoad.sum("e", ItErasureFixtures.DATASET, "userId", "note"),
            ErasureLoad.sum("f", ItErasureFixtures.DATASET, "userId", "secret"),
            ErasureLoad.sum("g", ItErasureFixtures.DATASET, "userId", "nothing"),
            ErasureLoad.sum("h", ItErasureFixtures.DATASET, "userId", "card"),
            ErasureLoad.count("i", ItErasureFixtures.DATASET, "userId").where("status", "OPEN"),
            // IN on a flag: its kind allows only an equality.
            ErasureLoad.count("j", ItErasureFixtures.DATASET, "userId").where("flag", true, false),
            ErasureLoad.count("k", ItErasureFixtures.DATASET, "userId").where("flag", true),
            ErasureLoad.sum("ok", ItErasureFixtures.DATASET, "userId", "amount").where("status", "OPEN", "USED")),
            new AtomicReference<>());
        // The same name with another condition elsewhere; the same conditions in another order, and 10L for 10, are
        // the same load.
        PersonalDataEraser other = eraser(List.of(
            ErasureLoad.count("i", ItErasureFixtures.DATASET, "userId").where("status", "USED"),
            ErasureLoad.sum("ok", ItErasureFixtures.DATASET, "userId", "amount").where("status", "USED", "OPEN")),
            new AtomicReference<>());
        PersonalDataEraser same = eraser(List.of(
            ErasureLoad.count("m", ItErasureFixtures.DATASET, "userId").where("status", "OPEN").where("amount", 10)),
            new AtomicReference<>());
        PersonalDataEraser reordered = eraser(List.of(
            ErasureLoad.count("m", ItErasureFixtures.DATASET, "userId").where("amount", 10L).where("status", "OPEN")),
            new AtomicReference<>());
        PersonalDataErasers erasers = erasers(broken, other, same, reordered);
        String location = "PersonalDataEraser " + broken.getClass().getName();
        String otherLocation = "PersonalDataEraser " + other.getClass().getName();
        MockEnvironment development = new MockEnvironment();
        development.setActiveProfiles("dev");
        assertThat(new AccountDeletionChecks(entries, erasers, datasets, definitions, development).check())
            .filteredOn(p -> p.location().startsWith("PersonalDataEraser"))
            .extracting(p -> p.severity() + " " + p.location() + ": " + p.message())
            .containsExactlyInAnyOrder(
                "ERROR " + location + ": load a: entity ItErasurePoint has no field nothing (a condition)",
                "ERROR " + location + ": load b: entity ItErasurePoint: the condition on secret is on a sensitive or"
                    + " masked field",
                "ERROR " + location + ": load c: entity ItErasurePoint: the condition on card is on a sensitive or"
                    + " masked field",
                "ERROR " + location + ": load d: entity ItErasurePoint: the condition on status gives a value it"
                    + " cannot hold: " + codeRefusal(),
                "ERROR " + location + ": load e: entity ItErasurePoint adds up note, which is not a number or an"
                    + " amount",
                "ERROR " + location + ": load f: entity ItErasurePoint adds up secret, a sensitive or masked field",
                "ERROR " + location + ": load g: entity ItErasurePoint has no field nothing (to add up)",
                "ERROR " + location + ": load h: entity ItErasurePoint adds up card, a sensitive or masked field",
                "ERROR " + location + ": load j: entity ItErasurePoint: the condition on flag needs IN, which the"
                    + " field does not allow",
                "ERROR " + otherLocation + ": load i is declared with another dataset, field, kind or conditions"
                    + " elsewhere");
    }

    /** What the coercer says of a code not in the field's list. */
    private static String codeRefusal() {
        try {
            com.jabiz.entity.FieldValueCoercer.coerce(ItErasureFixtures.ENTITY.findField("status").orElseThrow(),
                "GONE", true);
            throw new AssertionError("GONE was accepted");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
