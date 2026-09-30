package com.jabiz.runtime.integrity;

/** Permission codes of the integrity seals (docs/design/21-audit-retention.md section 2). */
public final class IntegrityPermissions {

    /** Read the seals, the head of the chain and the verifications. */
    public static final String READ = "integrity.read";
    /** Run {@code INTEGRITY_SEAL} (the job does, as the system actor). */
    public static final String SEAL = "integrity.seal";
    /** Run {@code INTEGRITY_VERIFY}. */
    public static final String VERIFY = "integrity.verify";

    private IntegrityPermissions() {}
}
