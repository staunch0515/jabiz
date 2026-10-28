package com.quizbuks.app;

import android.net.Uri;

/**
 * Opens the takers' app in a Trusted Web Activity (from Bubblewrap's template). Every address it opens on the app's
 * host carries {@code shell=android}, the launcher's as well as an App Link's from a mail, so the page knows it runs in
 * the store app (quizbuks-web/app/src/shell.ts) and offers no home-screen install. The orientation is the page's own
 * (twa-manifest.json `default`), so the template's orientation code is left out.
 */
public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {

    static final String SHELL_PARAMETER = "shell";
    static final String SHELL = "android";

    @Override
    protected Uri getLaunchingUrl() {
        return markShell(super.getLaunchingUrl());
    }

    static Uri markShell(Uri uri) {
        if (uri == null || !BuildConfig.HOST.equals(uri.getHost())
                || SHELL.equals(uri.getQueryParameter(SHELL_PARAMETER))) {
            return uri;
        }
        return uri.buildUpon().appendQueryParameter(SHELL_PARAMETER, SHELL).build();
    }
}
