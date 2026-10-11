package com.jabiz.inbound;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The signature schemes the platform knows, by name (decision D37 item 1). */
public final class InboundSchemes {

    private static final Map<String, InboundSignatureScheme> SCHEMES = Map.of(
        StripeSignature.NAME, new StripeSignature(),
        HmacSha256Signature.NAME, new HmacSha256Signature());

    private InboundSchemes() {
    }

    public static Optional<InboundSignatureScheme> of(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(SCHEMES.get(name));
    }

    /** The names, sorted. */
    public static List<String> names() {
        return SCHEMES.keySet().stream().sorted().toList();
    }
}
