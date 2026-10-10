package com.jabiz.quizbuks.content;

/**
 * The label of a quiz version (docs/quizbuks/plans/Q3-content.md, D-Q3-3): version 1 is {@code v1.0}, version 2
 * {@code v1.1}, version 11 {@code v1.10}. Written into the version when it is made, so no frontend works it out.
 */
public final class VersionLabels {

    public static String label(int versionNo) {
        if (versionNo < 1) {
            throw new IllegalArgumentException("Versions are numbered from 1, not " + versionNo);
        }
        return "v1." + (versionNo - 1);
    }

    private VersionLabels() {}
}
