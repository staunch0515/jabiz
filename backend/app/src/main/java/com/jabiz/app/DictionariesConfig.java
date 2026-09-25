package com.jabiz.app;

import com.jabiz.dictionary.StaticDictionary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Labels of the dictionaries declared in code (docs/design/02-metamodel.md section 5). */
@Configuration
class DictionariesConfig {

    @Bean
    StaticDictionary waybillStatusDictionary() {
        return StaticDictionary.define(WaybillEntityDefinitions.STATUS_DICTIONARY, d -> d
            .item("CREATED", "zh", "已创建", "ja", "作成済み", "en", "Created")
            .item("IN_TRANSIT", "zh", "运输中", "ja", "輸送中", "en", "In transit")
            .item("CUSTOMS_CLEARED", "zh", "已清关", "ja", "通関済み", "en", "Customs cleared")
            .item("DELIVERED", "zh", "已送达", "ja", "配達済み", "en", "Delivered"));
    }
}
