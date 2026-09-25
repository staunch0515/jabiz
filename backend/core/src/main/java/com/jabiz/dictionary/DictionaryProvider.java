package com.jabiz.dictionary;

import java.util.List;
import java.util.Locale;

/**
 * Source of dictionary entries (docs/design/02-metamodel.md section 5). Synchronous: the platform caches the
 * result and calls providers off the request threads, so an implementation may read from anywhere.
 */
public interface DictionaryProvider {

    boolean supports(String dictUrn);

    /** Entries of the dictionary with labels in {@code locale}, in display order. */
    List<DictItem> items(String dictUrn, Locale locale);
}
