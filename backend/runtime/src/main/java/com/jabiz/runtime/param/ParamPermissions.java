package com.jabiz.runtime.param;

/** Permission codes of business parameters (docs/design/04-temporal-append-only.md section 9). */
public final class ParamPermissions {

    /** Reading parameters through their dataset. */
    public static final String READ = "platform.param.read";
    /**
     * Writing parameters: through their dataset and by the processes that declare, change or schedule them. One code
     * for all: the dataset can insert parameters too, so a separate code for declaring them would not hold.
     */
    public static final String WRITE = "platform.param.write";

    private ParamPermissions() {}
}
