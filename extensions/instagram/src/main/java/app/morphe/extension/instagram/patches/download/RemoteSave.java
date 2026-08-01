/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import app.morphe.extension.crimera.downloader.RemoteSink;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;

/**
 * Turns the remote-save preferences into a {@link RemoteSink}, or into nothing when
 * they are incomplete. Keeping the check in one place is what lets the download dialog
 * hide the remote entries instead of offering an option that fails on tap.
 */
public class RemoteSave {

    private static final int DEFAULT_PORT = 22;

    /** True when the feature is on and has enough to attempt a connection. */
    public static boolean isConfigured() {
        if (!Pref.remoteSave() || !SettingsStatus.downloadMedia) return false;
        return !isBlank(Pref.remoteSaveHost())
                && !isBlank(Pref.remoteSaveUser())
                && !isBlank(Pref.remoteSavePath());
    }

    /** Whether a direct (dialog-less) download should go remote. */
    public static boolean isDirectDefault() {
        return isConfigured() && Pref.remoteSaveDirect();
    }

    /** A sink for one batch of files, or null when the settings are incomplete. */
    public static RemoteSink sink() {
        if (!isConfigured()) return null;
        return new SftpSink(
                Pref.remoteSaveHost().trim(),
                port(),
                Pref.remoteSaveUser().trim(),
                Pref.remoteSavePassword(),
                Pref.remoteSavePath().trim()
        );
    }

    private static int port() {
        try {
            int parsed = Integer.parseInt(Pref.remoteSavePort().trim());
            if (parsed > 0 && parsed <= 65535) return parsed;
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_PORT;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
