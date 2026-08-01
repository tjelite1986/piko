/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.crimera.downloader;

public class DownloadRequest {
    public String url;
    public String subFolder;
    public String fileName;
    /**
     * What to do when the target file already exists: skip the download (false, the
     * default) or keep the existing file and save this one under a numbered name
     * (true). The flag lives here rather than being read from settings inside the
     * downloader, because the downloader is shared between apps that each have their
     * own preference store.
     */
    public boolean allowDuplicate;
    /**
     * Where the file lands. Null — the default — means the device's download folder;
     * set, the bytes are streamed through the device and uploaded instead, and nothing
     * is written to local storage. Like {@link #allowDuplicate} it rides on the request
     * so the shared downloader never reads either app's preference store.
     */
    public RemoteSink remote;

    public DownloadRequest(String url, String subFolder, String fileName) {
        this(url, subFolder, fileName, false);
    }

    public DownloadRequest(String url, String subFolder, String fileName, boolean allowDuplicate) {
        this(url, subFolder, fileName, allowDuplicate, null);
    }

    public DownloadRequest(String url, String subFolder, String fileName, boolean allowDuplicate, RemoteSink remote) {
        this.url = url;
        this.subFolder = subFolder;
        this.fileName = fileName;
        this.allowDuplicate = allowDuplicate;
        this.remote = remote;
    }
}