package com.jabiz.quizbuks.content.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** F-C7a: contact details are taken out in every writing, and ordinary numbers are left alone. */
class PersonalDetailsTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "0312345678", "09012345678", "+819012345678", "03(1234)5678", "(03)1234-5678", "Fax.03-1234-5678",
        "tel03-1234-5678", "０３－１２３４－５６７８", "03‐1234‐5678", "03－1234－5678",
        "03-1234-5678", "090 1234 5678", "+81 90 1234 5678", "+81-90-1234-5678", "03.1234.5678", "TEL：03-1234-5678"})
    void aTelephoneNumberIsRemoved(String number) {
        String text = "Call " + number + " today";
        PersonalDetails.Cleaned cleaned = PersonalDetails.clean(text);
        assertThat(cleaned.changed()).isTrue();
        assertThat(cleaned.text()).doesNotContainPattern("\\d{3}").startsWith("Call").endsWith("today");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "info@example.com", "info＠example.com", "info[at]example.com", "taro(at)example.co.jp", "info@例え.jp",
        "Info@Example.COM", "a.b+c@sub.example.org", "info@example.comまでご連絡ください"})
    void anEmailAddressIsRemoved(String address) {
        PersonalDetails.Cleaned cleaned = PersonalDetails.clean("Mail " + address);
        assertThat(cleaned.changed()).isTrue();
        assertThat(cleaned.text()).doesNotContain("example").doesNotContain("例え").doesNotContain("@");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Size 10-20-300 mm", "2020-2021-2022 seasons", "price 1.200.500", "型番 AB 12-345-6789", "Date 2026-01-31",
        "Founded in 1998, 2004 and 2010", "Order 1234567890", "x0312345678y", "Version 0.9.1", "Weight 0.5 kg, 10 cm",
        "Code AB-0312345678", "https://example.com/0312345678", "¥1,200,500", "Room 301 and 302"})
    void ordinaryNumbersAreNotTouched(String text) {
        PersonalDetails.Cleaned cleaned = PersonalDetails.clean(text);
        assertThat(cleaned.changed()).isFalse();
        assertThat(cleaned.text()).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "LINE: @shopname", "LINE ID: shop_name", "LINE @shopname", "公式LINE：@shopname", "Instagram: shop.name",
        "ＬＩＮＥ：@shopname", "X @shopname", "微信：shop123"})
    void aSocialAccountIsRemoved(String account) {
        PersonalDetails.Cleaned cleaned = PersonalDetails.clean("Follow us. " + account + " Thanks");
        assertThat(cleaned.changed()).isTrue();
        assertThat(cleaned.text()).doesNotContain("shop").startsWith("Follow us.").endsWith("Thanks");
    }

    @Test
    void aLinkToASocialServiceGoesAndTheOtherLinksStay() {
        assertThat(PersonalDetails.clean("See [us on X](https://x.com/shop) and https://www.instagram.com/shop/ now")
            .text()).isEqualTo("See us on X and now");
        PersonalDetails.Cleaned own = PersonalDetails.clean("See [the shop](https://shop.example.com/a?b=1)");
        assertThat(own.changed()).isFalse();
    }

    @Test
    void mailtoAndTelLinksAreNeutralised() {
        assertThat(PersonalDetails.clean("[Mail](mailto:a@b.com) [Call](tel:0312345678)").text())
            .isEqualTo("[Mail](#) [Call](#)");
    }

    @Test
    void aNumberFollowedByAnotherNumberIsStillTaken() {
        assertThat(PersonalDetails.clean("03-1234-5678 2024").text()).isEqualTo("2024");
    }
}
