/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.downloader.RemoteSink;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Uploads a profile on its own — no media — as a {@code <username>.json} sidecar.
 * <p>
 * The receiving library gives a creator a picture and a bio from the profile fields
 * a download's sidecar already carries ({@link PostMetadata}), which covers everything
 * fetched from now on but nothing that arrived earlier. Asking Instagram's API for
 * those profiles from a server is not an option: it answers 429 to the first request
 * that does not come from a real session. This is that same data, read out of the
 * session the app is already logged into, for a profile whose posts are long since
 * downloaded.
 * <p>
 * Everything here comes from the object the app holds in memory, so it costs no
 * network request of its own and cannot be rate limited.
 */
public class ProfileMetadata {

    /** Matches PostMetadata: the profile fields are the same shape in both. */
    private static final int SCHEMA_VERSION = 2;

    /** A UserData getter, which may throw when Instagram's object shape differs. */
    private interface Field<T> {
        T get() throws Exception;
    }

    private static <T> T read(Field<T> field) {
        try {
            return field.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sends this profile to the configured server. Silent about success only when
     * there is nothing to say: every outcome, including a refused connection, ends
     * in a toast, because the user tapped a menu item and deserves an answer.
     */
    public static void saveToServer(Context context, UserData user) {
        if (!RemoteSave.isConfigured()) {
            Utils.showToastShort(str("piko_remote_save_not_configured"));
            return;
        }
        if (!Utils.isNetworkConnected()) {
            Utils.showToastShort(str("piko_no_internet"));
            return;
        }

        String username = read(user::getUsername);
        if (username == null || username.isEmpty()) {
            Utils.showToastShort(str("piko_profile_save_failed"));
            return;
        }

        String json;
        try {
            JSONObject out = new JSONObject();
            out.put("schema_version", SCHEMA_VERSION);
            out.put("source", "instagram");
            // Says what this file is: the receiver treats a lone json as a profile,
            // and this is what tells a human reading the folder the same thing.
            out.put("kind", "profile");
            out.put("saved_at", timestamp());
            out.put("username", username);
            out.put("full_name", read(user::getFullName));
            out.put("user_id", read(user::getUserId));
            out.put("bio", read(user::getBio));
            // A URL, not the bytes: the receiving side fetches it once, and the CDN
            // does not refuse that the way the API refuses a profile lookup.
            out.put("profile_pic_url", read(user::getProfilePictureUrl));
            out.put("is_verified", read(user::isVerified));
            out.put("profile_url", read(user::getProfileLink));
            json = out.toString(2);
        } catch (Exception e) {
            Logger.printException(() -> "Could not build the profile sidecar", e);
            Utils.showToastShort(str("piko_profile_save_failed"));
            return;
        }

        final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        final String subFolder = DownloadUtils.getSubfolderName(username);
        final String fileName = username + ".json";

        // Its own thread with its own sink: this is a single file, not a batch, and
        // the downloader only closes a connection when a download queue drains.
        new Thread(() -> {
            RemoteSink sink = RemoteSave.sink();
            if (sink == null) {
                Utils.showToastShort(str("piko_remote_save_not_configured"));
                return;
            }
            try {
                sink.uploadBytes(payload, subFolder, fileName);
                Utils.showToastShort(str("piko_profile_saved") + " " + sink.describe());
            } catch (Exception e) {
                PikoUtils.logger(e);
                Logger.printException(() -> "Profile upload failed", e);
                Utils.showToastShort(str("piko_profile_save_failed") + ": " + e.getMessage());
            } finally {
                try {
                    sink.close();
                } catch (Exception ignored) {
                    // Closing is best effort; the upload has already been reported.
                }
            }
        }, "piko-profile-upload").start();
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
