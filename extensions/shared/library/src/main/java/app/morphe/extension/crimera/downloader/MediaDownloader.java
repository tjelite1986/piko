/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.crimera.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.database.Cursor;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import app.morphe.extension.crimera.constants.ExtensionStrings;
import app.morphe.extension.crimera.PikoUtils;

public class MediaDownloader {
    private static final String CHANNEL_ID = "media_download_channel";
    private final Context context;
    private final NotificationManager notificationManager;
    private final LinkedBlockingQueue<DownloadRequest> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isDownloading = false;
    /** The sink of the batch in flight, kept so it can be closed when the queue drains. */
    private RemoteSink lastRemote;

    public MediaDownloader(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void enqueue(DownloadRequest request) {
        // A remote download never touches local storage, so the folder grant that the
        // local path cannot work without is not asked for here.
        if (request.remote == null && !StorageUtils.checkStoragePermissions()) {
            StorageUtils.allowStorageAccess();
            return;
        }
        queue.add(request);
        processNext();
    }

    /**
     * Writes JSON straight to the download folder instead of fetching it, for sidecar
     * files that accompany a media download (post metadata and the like).
     * <p>
     * It runs on the same single-thread executor as the downloads, so the sidecar is
     * written after the media file it belongs to and never races with it — which holds
     * only if the caller enqueued that media first. Ordering is all it guarantees: the
     * sidecar is written whether or not that download succeeded, which is what lets a
     * re-download fill in metadata for a file that is already on disk. No notification
     * is posted: a sidecar is a byproduct of a download the user already got told about.
     */
    public void enqueueJson(String content, String subFolder, String fileName) {
        enqueueJson(content, subFolder, fileName, null);
    }

    /** As above, but uploaded to {@code remote} when one is given. */
    public void enqueueJson(String content, String subFolder, String fileName, RemoteSink remote) {
        if (remote == null && !StorageUtils.checkStoragePermissions()) {
            StorageUtils.allowStorageAccess();
            return;
        }
        executor.execute(() -> {
            if (remote == null) {
                writeJsonFile(content, subFolder, fileName);
            } else {
                try {
                    remote.uploadBytes(content.getBytes(StandardCharsets.UTF_8), subFolder, fileName);
                } catch (Exception e) {
                    // A missing sidecar must never look like a failed download.
                    PikoUtils.logger(e);
                }
            }
        });
    }

    private void writeJsonFile(String content, String subFolder, String fileName) {
        try {
            Uri targetDirectoryUri = getTargetDirectoryUri(subFolder);
            // Same rule as the media itself: an existing file is left alone.
            if (findChildDocument(targetDirectoryUri, fileName, null) != null) return;

            Uri outputDocumentUri = DocumentsContract.createDocument(
                    context.getContentResolver(),
                    targetDirectoryUri,
                    "application/json",
                    fileName
            );
            if (outputDocumentUri == null) {
                throw new IOException("Could not create sidecar file " + fileName);
            }

            try (OutputStream output = context.getContentResolver().openOutputStream(outputDocumentUri)) {
                if (output == null) {
                    throw new IOException("Could not open sidecar file " + fileName);
                }
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Exception e) {
            // A missing sidecar must never look like a failed download.
            PikoUtils.logger(e);
        }
    }

    private void processNext() {
        if (isDownloading) return;
        if (queue.isEmpty()) {
            closeRemote();
            return;
        }
        isDownloading = true;
        DownloadRequest request = queue.poll();
        if (request != null) {
            if (request.remote != null) lastRemote = request.remote;
            executor.execute(() -> runDownloadTask(request));
        }
    }

    /**
     * Hangs up the remote connection once the batch is done. Queued on the executor
     * rather than closed here, so it lands behind the sidecar writes that were handed
     * to the same thread while the last media file was still uploading.
     */
    private void closeRemote() {
        RemoteSink sink = lastRemote;
        if (sink == null) return;
        lastRemote = null;
        executor.execute(sink::close);
    }

    private void runDownloadTask(DownloadRequest request) {
        if (request.remote != null) {
            runRemoteTask(request);
            return;
        }
        int notificationId = (int) System.currentTimeMillis();
        Uri outputDocumentUri = null;
        boolean downloadCompleted = false;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }
        String downloadStartString = ExtensionStrings.DOWNLOAD_ONGOING + request.fileName;
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(downloadStartString)
                .setOngoing(true) // Keeps notification un-swipable during download execution.
                .setProgress(100, 0, false);

        notificationManager.notify(notificationId, builder.build());

        try {
            Uri targetDirectoryUri = getTargetDirectoryUri(request.subFolder);
            String fileName = request.fileName;
            if (findChildDocument(targetDirectoryUri, fileName, null) != null) {
                if (!request.allowDuplicate) {
                    showToast(ExtensionStrings.DOWNLOAD_MEDIA_EXISTS);
                    notificationManager.cancel(notificationId);
                    return;
                }
                fileName = nextFreeName(targetDirectoryUri, fileName);
            }

            outputDocumentUri = DocumentsContract.createDocument(
                    context.getContentResolver(),
                    targetDirectoryUri,
                    getMimeType(fileName),
                    fileName
            );
            if (outputDocumentUri == null) {
                throw new IOException("Could not create download file");
            }

            showToast(downloadStartString);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(request.url);
                conn = (HttpURLConnection) url.openConnection();
                conn.connect();

                int length = conn.getContentLength();
                try (InputStream input = new BufferedInputStream(conn.getInputStream());
                     OutputStream output = context.getContentResolver().openOutputStream(outputDocumentUri)) {
                    if (output == null) {
                        throw new IOException("Could not open download file");
                    }

                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    long lastUpdateTime = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        output.write(buffer, 0, count);

                        if (length > 0) {
                            int per = (int) (total * 100 / length);

                            // Cap the percentage at 99 inside the loop so it NEVER shows 100% until it actually completes
                            if (per >= 100) per = 99;

                            // Performance optimization: Only notify the system every 200ms to avoid clogging the OS thread
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateTime > 200) {
                                builder.setProgress(100, per, false);
                                notificationManager.notify(notificationId, builder.build());
                                lastUpdateTime = currentTime;
                            }
                        }
                    }
                    output.flush();
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            downloadCompleted = true;

            final int finalNotificationId = notificationId;
            // The resolved name, which differs from the requested one when a duplicate
            // was renamed — the notification should name the file that actually landed.
            final String finalFileName = fileName;
            final String downloadCompletedString = ExtensionStrings.DOWNLOAD_COMPLETED + finalFileName;

            mainHandler.post(() -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(downloadCompletedString)
                        .setContentText(finalFileName)
                        .setOngoing(false) // This unlocks the swipe lock completely
                        .setProgress(0, 0, false); // Wipes the progress track bar layout away entirely
                // Force post the update layout
                notificationManager.notify(finalNotificationId, builder.build());

                try {
                    PikoUtils.toast(downloadCompletedString);
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            if (!downloadCompleted && outputDocumentUri != null) {
                try {
                    DocumentsContract.deleteDocument(context.getContentResolver(), outputDocumentUri);
                } catch (Exception ignored) {}
            }
            showToast(ExtensionStrings.DOWNLOAD_ERROR + e.getMessage());
            notificationManager.cancel(notificationId);
            PikoUtils.logger(e);
        } finally {
            isDownloading = false;
            processNext();
        }
    }

    /**
     * The remote counterpart of {@link #runDownloadTask}: fetch to a cache file, hand it
     * to the sink, delete it again. It goes via disk rather than piping the response
     * straight into the upload because the sink has to know the size up front and a
     * failed fetch should never reach the server as a truncated file.
     */
    private void runRemoteTask(DownloadRequest request) {
        int notificationId = (int) System.currentTimeMillis();
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }
        String startString = ExtensionStrings.DOWNLOAD_ONGOING + request.fileName;
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(startString)
                .setContentText(request.remote.describe())
                .setOngoing(true)
                .setProgress(100, 0, false);
        notificationManager.notify(notificationId, builder.build());

        File temporary = null;
        try {
            showToast(startString);
            temporary = File.createTempFile("piko_remote_", null, context.getCacheDir());

            HttpURLConnection conn = null;
            try {
                URL url = new URL(request.url);
                conn = (HttpURLConnection) url.openConnection();
                conn.connect();

                int length = conn.getContentLength();
                try (InputStream input = new BufferedInputStream(conn.getInputStream());
                     OutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    long lastUpdateTime = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        output.write(buffer, 0, count);

                        if (length > 0) {
                            // Half the bar is the fetch, the other half the upload.
                            int per = (int) (total * 50 / length);
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateTime > 200) {
                                builder.setProgress(100, per, false);
                                notificationManager.notify(notificationId, builder.build());
                                lastUpdateTime = currentTime;
                            }
                        }
                    }
                    output.flush();
                }
            } finally {
                if (conn != null) conn.disconnect();
            }

            builder.setProgress(100, 50, false);
            notificationManager.notify(notificationId, builder.build());

            boolean stored = request.remote.upload(
                    temporary, request.subFolder, request.fileName, request.allowDuplicate);
            if (!stored) {
                showToast(ExtensionStrings.DOWNLOAD_MEDIA_EXISTS);
                notificationManager.cancel(notificationId);
                return;
            }

            final int finalNotificationId = notificationId;
            final String completedString = ExtensionStrings.DOWNLOAD_COMPLETED + request.fileName;
            final String destination = request.remote.describe();
            mainHandler.post(() -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle(completedString)
                        .setContentText(destination)
                        .setOngoing(false)
                        .setProgress(0, 0, false);
                notificationManager.notify(finalNotificationId, builder.build());

                try {
                    PikoUtils.toast(completedString);
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            showToast(ExtensionStrings.DOWNLOAD_ERROR + e.getMessage());
            notificationManager.cancel(notificationId);
            PikoUtils.logger(e);
        } finally {
            if (temporary != null) {
                // noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            isDownloading = false;
            processNext();
        }
    }

    private void showToast(String msg) {
        mainHandler.post(() -> PikoUtils.toast(msg));
    }

    /**
     * Finds a free name next to an existing file by counting up: "clip.mp4" becomes
     * "clip (1).mp4", then "clip (2).mp4". Gives up after a sane number of tries so a
     * directory that cannot be read never turns into an endless loop.
     */
    private String nextFreeName(Uri targetDirectoryUri, String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";

        for (int index = 1; index <= 999; index++) {
            String candidate = base + " (" + index + ")" + extension;
            if (findChildDocument(targetDirectoryUri, candidate, null) == null) {
                return candidate;
            }
        }
        // Every slot taken: fall back to the original name and let createDocument
        // decide, rather than silently dropping the download.
        return fileName;
    }

    private Uri getTargetDirectoryUri(String subFolder) throws Exception {
        Uri treeUri = StorageUtils.getDownloadTreeUri();
        if (treeUri == null) {
            throw new IOException("Download folder access is missing");
        }

        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
        );

        if (subFolder == null || subFolder.isBlank()) {
            return parentUri;
        }

        for (String folderName : subFolder.split("/")) {
            if (!folderName.isBlank()) {
                parentUri = getOrCreateDirectory(parentUri, folderName);
            }
        }

        return parentUri;
    }

    private Uri getOrCreateDirectory(Uri parentUri, String folderName) throws Exception {
        Uri existingDirectoryUri = findChildDocument(parentUri, folderName, DocumentsContract.Document.MIME_TYPE_DIR);
        if (existingDirectoryUri != null) {
            return existingDirectoryUri;
        }

        Uri directoryUri = DocumentsContract.createDocument(
                context.getContentResolver(),
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName
        );
        if (directoryUri == null) {
            throw new IOException("Could not create folder " + folderName);
        }

        return directoryUri;
    }

    private Uri findChildDocument(Uri parentUri, String displayName, String mimeType) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri)
        );
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = context.getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }

            while (cursor.moveToNext()) {
                String childDocumentId = cursor.getString(0);
                String childDisplayName = cursor.getString(1);
                String childMimeType = cursor.getString(2);
                boolean nameMatches = displayName.equals(childDisplayName);
                boolean mimeTypeMatches = mimeType == null || mimeType.equals(childMimeType);
                if (nameMatches && mimeTypeMatches) {
                    return DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocumentId);
                }
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }

        return null;
    }

    private String getMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }
}
