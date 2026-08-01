/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.downloader.MediaDownloader;
import app.morphe.extension.crimera.downloader.RemoteSink;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;

/**
 * Writes a JSON sidecar next to a downloaded media file, carrying the post details
 * the file name alone cannot hold — caption above all.
 * <p>
 * Everything here is read from the media object the app already holds in memory, so
 * writing a sidecar costs no network request and cannot be rate limited.
 */
public class PostMetadata {

    /** Bumped when the field layout changes, so consumers can tell the shapes apart. */
    private static final int SCHEMA_VERSION = 1;

    /** A MediaData getter, which may throw when Instagram's object shape differs. */
    private interface Field<T> {
        T get() throws Exception;
    }

    /** Reads one field, treating an unavailable one as absent rather than fatal. */
    private static <T> T read(Field<T> field) {
        try {
            return field.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param mediaFileName file name of the media this sidecar belongs to, extension included
     */
    public static void write(MediaDownloader downloader, MediaData post, MediaData media,
                             int mediaIndex, String subFolder, String mediaFileName) {
        write(downloader, post, media, mediaIndex, subFolder, mediaFileName, null);
    }

    /** As above, but written to {@code remote} when the media went there too. */
    public static void write(MediaDownloader downloader, MediaData post, MediaData media,
                             int mediaIndex, String subFolder, String mediaFileName,
                             RemoteSink remote) {
        try {
            JSONObject json = new JSONObject();
            json.put("schema_version", SCHEMA_VERSION);
            json.put("source", "instagram");
            json.put("media_file", mediaFileName);
            json.put("downloaded_at", timestamp());

            UserData user = read(post::getUserData);
            if (user != null) {
                json.put("username", read(user::getUsername));
                json.put("full_name", read(user::getFullName));
                json.put("user_id", read(user::getUserId));
            }

            String shortcode = read(post::getShortcode);
            json.put("shortcode", shortcode);
            json.put("post_id", read(post::getPostID));
            json.put("media_pk", read(media::getMediaPkId));
            if (shortcode != null && !shortcode.isEmpty()) {
                json.put("post_url", "https://www.instagram.com/p/" + shortcode + "/");
            }

            Object postType = read(post::getPostType);
            if (postType != null) json.put("post_type", postType.toString());
            json.put("is_video", read(media::isVideo));

            Integer carouselSize = read(post::getCarouselSize);
            if (carouselSize != null) json.put("carousel_size", carouselSize);
            json.put("media_index", mediaIndex);

            json.put("caption", read(post::getDescriptionText));

            JSONArray mentions = new JSONArray();
            java.util.HashSet<UserData> mentionSet = read(post::getMentionSet);
            if (mentionSet != null) {
                for (UserData mentioned : mentionSet) {
                    String name = read(mentioned::getUsername);
                    if (name != null) mentions.put(name);
                }
            }
            json.put("mentions", mentions);

            // The CDN link is signed and expires — kept for tracing a file back to
            // the request that fetched it, not for re-downloading later.
            json.put("media_url", read(media::getMediaLink));

            downloader.enqueueJson(json.toString(2), subFolder, sidecarName(mediaFileName), remote);
        } catch (Exception e) {
            // The media download itself has already been queued; never fail it over this.
            PikoUtils.logger(e);
        }
    }

    /** Replaces the media extension with .json so the pair shares one base name. */
    private static String sidecarName(String mediaFileName) {
        int dot = mediaFileName.lastIndexOf('.');
        String base = dot > 0 ? mediaFileName.substring(0, dot) : mediaFileName;
        return base + ".json";
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
