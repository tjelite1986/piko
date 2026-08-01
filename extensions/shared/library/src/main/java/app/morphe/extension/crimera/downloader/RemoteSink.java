/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.crimera.downloader;

import java.io.File;

/**
 * A destination outside the device that a download can be routed to instead of the
 * local download folder.
 * <p>
 * The interface lives here so {@link MediaDownloader} can stay free of any transport
 * dependency: the implementation (and whatever library it needs) belongs to the app
 * extension that offers the feature. One sink instance serves one batch of files and
 * is closed by the downloader when the queue drains, so a connection can be reused
 * across the slides of a carousel rather than reopened per file.
 */
public interface RemoteSink {

    /**
     * Uploads a file. {@code subFolder} is created when missing, relative to whatever
     * base location the sink was configured with.
     *
     * @return true when the file was stored, false when an identical name was already
     *         there and duplicates were not allowed.
     */
    boolean upload(File source, String subFolder, String fileName, boolean allowDuplicate) throws Exception;

    /** Uploads in-memory content — used for the metadata sidecar. */
    void uploadBytes(byte[] content, String subFolder, String fileName) throws Exception;

    /** Releases the connection. Called once per batch; must tolerate being called twice. */
    void close();

    /** Short "user@host" style label for toasts and notifications. */
    String describe();
}
