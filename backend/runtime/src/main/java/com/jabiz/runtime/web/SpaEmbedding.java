package com.jabiz.runtime.web;

import java.util.Optional;

/**
 * Who may frame the pages of an SPA that another platform embeds (decision D59; docs/design/17-apps-and-branches.md
 * section 3.2): the security side knows the external token providers and their tenants.
 */
public interface SpaEmbedding {

    /**
     * The {@code frame-ancestors} sources for a page of an SPA embedded by {@code provider}, opened with
     * {@code tenant} (the provider's tenant parameter of the request); empty when the provider is unusable or the
     * tenant is not one of its tenants, and the page is then not framed at all.
     */
    Optional<String> frameAncestors(String provider, String tenant);

    /** The provider's query parameter naming the tenant; empty for an unusable provider. */
    Optional<String> tenantParameter(String provider);

    /** No provider: nothing is framed. */
    SpaEmbedding NONE = new SpaEmbedding() {
        @Override
        public Optional<String> frameAncestors(String provider, String tenant) {
            return Optional.empty();
        }

        @Override
        public Optional<String> tenantParameter(String provider) {
            return Optional.empty();
        }
    };
}
