package com.jabiz.culture;

import com.jabiz.dictionary.StaticDictionary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.culture.Culture.*;
import static com.jabiz.culture.CultureEntities.*;

/**
 * Labels of the fixed codes. The media types, activity types and age groups are database dictionaries instead
 * (editors add values), filled by {@code CULTURE_SETUP}.
 */
@Configuration
public class CultureDictionaries {

    @Bean
    StaticDictionary cultureStoryStatus() {
        return StaticDictionary.define(STORY_STATUS, d -> d
            .item(DRAFT, "zh", "草稿", "ja", "下書き", "en", "Draft")
            .item(IN_REVIEW, "zh", "审核中", "ja", "確認待ち", "en", "In review")
            .item(PUBLISHED, "zh", "已发布", "ja", "公開中", "en", "Published")
            .item(UNPUBLISHED, "zh", "已下线", "ja", "非公開", "en", "Unpublished"));
    }

    @Bean
    StaticDictionary cultureParticipantStatus() {
        return StaticDictionary.define(PARTICIPANT_STATUS, d -> d
            .item(DRAFT, "zh", "草稿", "ja", "下書き", "en", "Draft")
            .item(ACTIVE, "zh", "公开", "ja", "公開中", "en", "Active")
            .item(HIDDEN, "zh", "隐藏", "ja", "非表示", "en", "Hidden")
            .item(WITHDRAWN, "zh", "已撤回同意", "ja", "同意撤回", "en", "Consent withdrawn"));
    }

    @Bean
    StaticDictionary cultureResourceStatus() {
        return StaticDictionary.define(RESOURCE_STATUS, d -> d
            .item(DRAFT, "zh", "草稿", "ja", "下書き", "en", "Draft")
            .item(PUBLISHED, "zh", "已发布", "ja", "公開中", "en", "Published"));
    }

    @Bean
    StaticDictionary cultureVisibility() {
        return StaticDictionary.define(VISIBILITY, d -> d
            .item(PRIVATE, "zh", "不公开", "ja", "非公開", "en", "Private")
            .item(PUBLIC, "zh", "公开", "ja", "公開", "en", "Public"));
    }

    @Bean
    StaticDictionary cultureVideoProvider() {
        return StaticDictionary.define(VIDEO_PROVIDER, d -> d
            .item("YOUTUBE", "zh", "YouTube", "ja", "YouTube", "en", "YouTube")
            .item("VIMEO", "zh", "Vimeo", "ja", "Vimeo", "en", "Vimeo"));
    }

    @Bean
    StaticDictionary cultureMediaKind() {
        return StaticDictionary.define(MEDIA_KIND, d -> d
            .item(PHOTO, "zh", "照片", "ja", "写真", "en", "Photo")
            .item(AUDIO, "zh", "音频", "ja", "音声", "en", "Audio"));
    }

    @Bean
    StaticDictionary cultureConsentParty() {
        return StaticDictionary.define(CONSENT_PARTY, d -> d
            .item(PARTY_PARTICIPANT, "zh", "本人", "ja", "本人", "en", "Participant")
            .item(PARTY_GUARDIAN, "zh", "监护人", "ja", "保護者", "en", "Guardian"));
    }
}
