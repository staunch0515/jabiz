package com.jabiz.dictionary;

import java.util.Optional;
import java.util.Set;

/**
 * Enabled codes of dictionaries, as seen by validation. The runtime loads the dictionaries a change needs
 * before validating it, so validation itself stays synchronous.
 */
@FunctionalInterface
public interface DictionaryLookup {

    /** Enabled codes of the dictionary, or empty when the dictionary is unknown to this lookup. */
    Optional<Set<String>> enabledCodes(String dictUrn);

    /** A lookup that knows no dictionary: dictionary-backed codes are not checked. */
    DictionaryLookup NONE = urn -> Optional.empty();
}
