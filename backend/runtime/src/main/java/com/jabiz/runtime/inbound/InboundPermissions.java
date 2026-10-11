package com.jabiz.runtime.inbound;

/** Permission codes of inbound webhooks (docs/design/11-ledger-events-jobs.md section 2.5; decision D37). */
public final class InboundPermissions {

    /** Read the received events, their bodies included (which may hold personal data). */
    public static final String READ = "inbound.read";
    /** The declared write permission of the read-only {@code InboundEvent} dataset; nothing writes through it. */
    public static final String WRITE = "inbound.write";
    /** Run {@code INBOUND_RECEIVE}; held by no one: only the platform's receiver (the system) runs it. */
    public static final String RECEIVE = "inbound.receive";

    private InboundPermissions() {}
}
