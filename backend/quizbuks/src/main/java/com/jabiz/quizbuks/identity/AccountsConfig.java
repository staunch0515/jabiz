package com.jabiz.quizbuks.identity;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.security.SecurityEntities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registers accounts (docs/quizbuks/plans/Q2-accounts.md): the three entities and their datasets, the two
 * dictionaries, the avatar policy, the approval subject, the six processes, the two subscriptions to the approval
 * events and the three mail templates. The sign-in guard is {@link QbSignInGuard}.
 *
 * <p>Each entity has a default dataset, read only by administrators ({@code qb.admin.users.read}) and written by the
 * processes only ({@code processOnlyWrites}; its write permission is one no role holds). A person reads their own
 * profile, onboarding and sign-in records through datasets limited to them ({@code userId} = the caller), read only and
 * without history. Bans have no such dataset: a banned person learns of it when signing in.
 */
@Configuration
public class AccountsConfig {

    private final String poolRef;

    AccountsConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbAccounts.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /**
     * One's own rows: read only (the write permission is one no role holds), without history. {@code userId} is the
     * caller's id as the field holds it.
     */
    private DatasetDefinition ownDataset(String id, String entity, String read,
        Function<RequestContext, Object> userId) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(read, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", userId)));
    }

    @Bean
    EntityDefinition qbProfileEntity() {
        return QbAccounts.PROFILE_ENTITY;
    }

    @Bean
    EntityDefinition qbSponsorEntity() {
        return QbAccounts.SPONSOR_ENTITY;
    }

    @Bean
    EntityDefinition qbUserBanEntity() {
        return QbAccounts.BAN_ENTITY;
    }

    @Bean
    DatasetDefinition qbProfileDataset() {
        return defaultDataset(QbAccounts.PROFILE);
    }

    @Bean
    DatasetDefinition qbSponsorDataset() {
        return defaultDataset(QbAccounts.SPONSOR);
    }

    @Bean
    DatasetDefinition qbUserBanDataset() {
        return defaultDataset(QbAccounts.BAN);
    }

    @Bean
    DatasetDefinition qbMyProfileDataset() {
        return ownDataset(QbAccounts.MY_PROFILE_DATASET, QbAccounts.PROFILE, QbPermissions.ACCOUNT_READ,
            RequestContext::actorId);
    }

    @Bean
    DatasetDefinition qbMySponsorDataset() {
        return ownDataset(QbAccounts.MY_SPONSOR_DATASET, QbAccounts.SPONSOR, QbPermissions.SPONSOR_ME,
            RequestContext::actorId);
    }

    /**
     * M-02: one's own sign-in records, of the platform's entity (the platform has no such endpoint). The user is a
     * reference (a UUID): a template binds the scope's value as it is given, so it is given as a UUID; a caller whose
     * id is none has no value, which the platform refuses (403 {@code SCOPE_UNAVAILABLE}).
     */
    @Bean
    DatasetDefinition qbMySignInsDataset() {
        return ownDataset(QbAccounts.MY_SIGN_INS_DATASET, SecurityEntities.LOGIN_RECORD, QbPermissions.ACCOUNT_READ,
            request -> AccountEditing.uuidOrNull(request.actorId()));
    }

    @Bean
    StaticDictionary qbCurrencyDictionary() {
        return StaticDictionary.define(QbAccounts.CURRENCY_DICTIONARY, d -> d
            .item("USD", "en", "US dollar", "zh", "美元", "ja", "米ドル")
            .item("EUR", "en", "Euro", "zh", "欧元", "ja", "ユーロ")
            .item("GBP", "en", "Pound sterling", "zh", "英镑", "ja", "英ポンド")
            .item("JPY", "en", "Japanese yen", "zh", "日元", "ja", "日本円")
            .item("CNY", "en", "Chinese yuan", "zh", "人民币", "ja", "人民元")
            .item("INR", "en", "Indian rupee", "zh", "印度卢比", "ja", "インド・ルピー"));
    }

    @Bean
    StaticDictionary qbSponsorReviewStatusDictionary() {
        return StaticDictionary.define(QbAccounts.REVIEW_STATUS_DICTIONARY, d -> d
            .item(SponsorStatus.PENDING, "en", "In review", "zh", "待审核", "ja", "審査待ち")
            .item(SponsorStatus.APPROVED, "en", "Approved", "zh", "已通过", "ja", "承認済み")
            .item(SponsorStatus.REJECTED, "en", "Sent back", "zh", "已驳回", "ja", "差し戻し"));
    }

    /** Sponsors' avatars: re-encoded without metadata, small. */
    @Bean
    FilePolicy qbSponsorAvatarPolicy() {
        return FilePolicy.define(QbAccounts.AVATAR_POLICY)
            .allow(MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(2 * FilePolicy.MB)
            .image(i -> i.maxPixels(25_000_000).variants(128, 256))
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_AVATAR_READ)
            .build();
    }

    @Bean
    ApprovalSubject qbSponsorApprovalSubject() {
        return QbAccounts.approvalSubject();
    }

    @Bean
    MailTemplate qbWelcomeMail() {
        return AccountMails.WELCOME;
    }

    @Bean
    MailTemplate qbSponsorApprovedMail() {
        return AccountMails.SPONSOR_APPROVED;
    }

    @Bean
    MailTemplate qbSponsorRejectedMail() {
        return AccountMails.SPONSOR_REJECTED;
    }

    @Bean
    AccountLinks qbAccountLinks(@Value("${quizbuks.links.sponsor:}") String sponsor,
        @Value("${jabiz.mail.base-url:}") String mailBaseUrl) {
        return AccountLinks.of(sponsor, mailBaseUrl);
    }

    @Bean
    ProcessDefinition<ProfileProcesses.ProfileInput, ProfileProcesses.ProfileOutput, ProcessContext>
        qbProfileSave() {
        return ProfileProcesses.save();
    }

    @Bean
    ProcessDefinition<SponsorProcesses.ApplyInput, SponsorProcesses.SponsorOutput, ProcessContext> qbSponsorApply(
        AccountLinks links) {
        return SponsorProcesses.apply(links);
    }

    @Bean
    ProcessDefinition<SponsorProcesses.ProfileInput, SponsorProcesses.SponsorOutput, ProcessContext>
        qbSponsorProfileSave() {
        return SponsorProcesses.profileSave();
    }

    @Bean
    ProcessDefinition<SponsorProcesses.ApprovalResultInput, SponsorProcesses.SponsorOutput, ProcessContext>
        qbSponsorApprovalResult(AccountLinks links) {
        return SponsorProcesses.approvalResult(links);
    }

    /** Administrators are not banned: they are the roles the administration's entry accepts (review S4). */
    @Bean
    ProcessDefinition<BanProcesses.BanInput, BanProcesses.BanOutput, ProcessContext> qbUserBan(
        Environment environment) {
        List<String> admins = Binder.get(environment).bind("jabiz.security.entries.admin.accepted-roles",
            Bindable.listOf(String.class)).orElse(List.of("*"));
        return BanProcesses.ban(admins.stream().map(String::strip).filter(code -> !code.isEmpty())
            .collect(Collectors.toSet()));
    }

    @Bean
    ProcessDefinition<BanProcesses.UnbanInput, BanProcesses.BanOutput, ProcessContext> qbUserUnban() {
        return BanProcesses.unban();
    }

    @Bean
    EventSubscription<SponsorProcesses.ApprovalResultInput> qbSponsorApprovedSubscription(
        ProcessDefinition<SponsorProcesses.ApprovalResultInput, SponsorProcesses.SponsorOutput, ProcessContext>
            qbSponsorApprovalResult) {
        return EventSubscription.of("qb.sponsor-approved", ApprovalProcesses.APPROVED_EVENT, qbSponsorApprovalResult,
            SponsorProcesses::resultInput);
    }

    @Bean
    EventSubscription<SponsorProcesses.ApprovalResultInput> qbSponsorRejectedSubscription(
        ProcessDefinition<SponsorProcesses.ApprovalResultInput, SponsorProcesses.SponsorOutput, ProcessContext>
            qbSponsorApprovalResult) {
        return EventSubscription.of("qb.sponsor-rejected", ApprovalProcesses.REJECTED_EVENT, qbSponsorApprovalResult,
            SponsorProcesses::resultInput);
    }
}
