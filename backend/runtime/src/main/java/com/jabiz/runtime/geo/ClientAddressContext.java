package com.jabiz.runtime.geo;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.function.Function;

/**
 * The client address of the HTTP request a process runs for (decision D74), handed to its steps in the Reactor
 * context: the process API puts it there (as {@code ClientAddresses} resolves it behind the trusted proxies), and
 * {@code LoadClientCountry} reads it. It is not in the request context, the process's input or its records: only the
 * country it stands for is used, and only within the request. Processes run by events, jobs or app proxies have none.
 */
public final class ClientAddressContext {

    private static final Object KEY = ClientAddressContext.class;

    private ClientAddressContext() {}

    /** Puts {@code address} into the context (nothing when null). */
    public static Function<Context, Context> with(String address) {
        return context -> address == null ? context : context.put(KEY, address);
    }

    /** The address in {@code view}, or null. */
    public static String of(ContextView view) {
        return view.getOrDefault(KEY, null);
    }
}
