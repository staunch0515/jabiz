package com.jabiz.runtime.web;

import com.jabiz.dictionary.DictItem;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dictionary.DictionaryRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Dictionary entries labelled in the language of the request, for select boxes and display. */
@RestController
@RequestMapping("/api/dictionaries")
class DictionaryController {

    private final DictionaryRegistry dictionaries;

    DictionaryController(DictionaryRegistry dictionaries) {
        this.dictionaries = dictionaries;
    }

    @GetMapping("/{urn}")
    Mono<List<DictItem>> items(@PathVariable String urn) {
        return RequestContexts.current().flatMap(request -> dictionaries.items(urn, request.locale()));
    }
}
