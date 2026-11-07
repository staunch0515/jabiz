package com.jabiz.runtime.geo;

import com.jabiz.runtime.web.ClientAddresses;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the caller is, by its own address (decision D74): for pages that show or hide content by region before any
 * process runs (a list that leaves out what the caller's region cannot use). Open to everyone, as it only tells the
 * caller about its own address; never cached ({@code Cache-Control: no-store}), so a shared cache cannot hand one
 * caller's answer to another. The address itself is not echoed, saved or logged. A page's answer is a convenience:
 * the processes check the country themselves ({@code LoadClientCountry}).
 */
@RestController
public class ClientRegionController {

    public static final String PATH = "/api/client/region";

    /**
     * @param country       the ISO 3166-1 alpha-2 code, or null when unknown
     * @param mainlandChina whether it is mainland China ({@code CN}); false when unknown
     * @param known         whether the country is known
     */
    public record ClientRegion(String country, boolean mainlandChina, boolean known) {}

    private final ClientAddresses clients;
    private final ClientCountries countries;

    public ClientRegionController(ClientAddresses clients, ClientCountries countries) {
        this.clients = clients;
        this.countries = countries;
    }

    @GetMapping(PATH)
    ResponseEntity<ClientRegion> region(ServerHttpRequest http) {
        com.jabiz.context.ClientCountry country = countries.countryOf(clients.addressOf(http));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Vary", "*")
            .body(new ClientRegion(country.code(), country.mainlandChina(), country.known()));
    }
}
