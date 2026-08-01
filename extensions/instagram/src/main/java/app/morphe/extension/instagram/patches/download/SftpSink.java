/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.Consumer;

import app.morphe.extension.crimera.downloader.RemoteSink;

/**
 * Uploads downloads to an SFTP server instead of the device's download folder.
 * <p>
 * Every file is written under a ".part" name and renamed into place once the last
 * byte is through. A server-side watcher (the elite-v2 import sweep, say) polls its
 * drop folder on a timer, and a half-transferred file under its final name is a file
 * that gets picked up half-transferred.
 * <p>
 * The session is opened on first use and reused for the rest of the batch, since a
 * carousel means one upload per slide plus a sidecar each.
 */
public class SftpSink implements RemoteSink {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String basePath;
    /** Pinned host key as "<type> <base64>", empty until the first connection learns it. */
    private final String knownHostKey;
    private final Consumer<String> onHostKeyLearned;

    private Session session;
    private ChannelSftp channel;

    public SftpSink(String host, int port, String username, String password, String basePath,
                    String knownHostKey, Consumer<String> onHostKeyLearned) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.basePath = normalize(basePath);
        this.knownHostKey = knownHostKey == null ? "" : knownHostKey.trim();
        this.onHostKeyLearned = onHostKeyLearned;
    }

    @Override
    public boolean upload(File source, String subFolder, String fileName, boolean allowDuplicate) throws Exception {
        String directory = ensureDirectory(subFolder);
        fileName = safeName(fileName);
        String target = directory + "/" + fileName;
        if (exists(target)) {
            if (!allowDuplicate) return false;
            target = directory + "/" + nextFreeName(directory, fileName);
        }
        try (InputStream input = new FileInputStream(source)) {
            put(input, target);
        }
        return true;
    }

    @Override
    public void uploadBytes(byte[] content, String subFolder, String fileName) throws Exception {
        String target = ensureDirectory(subFolder) + "/" + safeName(fileName);
        // Same rule as the local sidecar: an existing one is left alone.
        if (exists(target)) return;
        put(new java.io.ByteArrayInputStream(content), target);
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    @Override
    public String describe() {
        return username + "@" + host;
    }

    /** Opens the session, or throws with a message worth showing in a toast. */
    private ChannelSftp connect() throws Exception {
        if (channel != null && channel.isConnected()) return channel;

        JSch jsch = new JSch();
        // Trust on first use, then pin. A phone has no known_hosts and no way to prompt
        // mid-download, but accepting any key on every connection would hand the account
        // password to anyone able to answer for the host — and this traffic leaves the
        // local network. So the first connection records the key and every later one is
        // checked against it; a server that comes back with a different key is refused
        // rather than silently trusted. Clearing the saved key in settings is what makes
        // it learn a new one, after a genuine server rebuild.
        boolean pinned = !knownHostKey.isEmpty();
        if (pinned) {
            jsch.setKnownHosts(new ByteArrayInputStream(
                    (knownHostsLine() + "\n").getBytes(StandardCharsets.UTF_8)));
        }

        session = jsch.getSession(username, host, port);
        session.setPassword(password);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", pinned ? "yes" : "no");
        session.setConfig(config);
        session.setTimeout(30_000);
        session.connect(20_000);

        if (!pinned) {
            HostKey presented = session.getHostKey();
            if (presented != null && onHostKeyLearned != null) {
                onHostKeyLearned.accept(presented.getType() + " " + presented.getKey());
            }
        }

        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(20_000);
        return channel;
    }

    /**
     * The pinned key as an OpenSSH known_hosts entry. A non-default port belongs in
     * "[host]:port" brackets, which is the form JSch matches against.
     */
    private String knownHostsLine() {
        String hostField = port == 22 ? host : "[" + host + "]:" + port;
        return hostField + " " + knownHostKey;
    }

    private void put(InputStream input, String target) throws Exception {
        ChannelSftp sftp = connect();
        String partial = target + ".part";
        sftp.put(input, partial);
        try {
            sftp.rename(partial, target);
        } catch (SftpException e) {
            // Leaving a ".part" behind would be picked up by nothing and cleaned by
            // nobody; drop it so a retry starts from an empty slate.
            try {
                sftp.rm(partial);
            } catch (SftpException ignored) {
            }
            throw e;
        }
    }

    /** mkdir -p for the base path plus the per-creator subfolder. */
    private String ensureDirectory(String subFolder) throws Exception {
        ChannelSftp sftp = connect();
        String path = basePath;
        makeDirectory(sftp, path);
        if (subFolder != null && !subFolder.isBlank()) {
            for (String part : subFolder.split("/")) {
                if (part.isBlank()) continue;
                path = path + "/" + safeName(part);
                makeDirectory(sftp, path);
            }
        }
        return path;
    }

    private void makeDirectory(ChannelSftp sftp, String path) throws Exception {
        try {
            SftpATTRS attributes = sftp.stat(path);
            if (!attributes.isDir()) {
                throw new IOException(path + " exists but is not a folder");
            }
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
            sftp.mkdir(path);
        }
    }

    private boolean exists(String path) throws Exception {
        ChannelSftp sftp = connect();
        try {
            sftp.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
            throw e;
        }
    }

    /**
     * One path segment, with anything that could climb out of the target folder taken
     * out. Folder and file names are built from the post's username and caption, which
     * are values Instagram hands us rather than values we chose — a name of ".." or one
     * carrying a slash would otherwise write outside the configured folder.
     */
    private static String safeName(String name) {
        String cleaned = name == null ? "" : name.trim();
        StringBuilder out = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            out.append(c == '/' || c == '\\' || c < 0x20 ? '_' : c);
        }
        String result = out.toString().trim();
        if (result.isEmpty() || result.equals(".") || result.equals("..")) return "_";
        return result;
    }

    /** "clip.mp4" -> "clip (1).mp4", mirroring the local downloader's rule. */
    private String nextFreeName(String directory, String fileName) throws Exception {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";

        for (int index = 1; index <= 999; index++) {
            String candidate = base + " (" + index + ")" + extension;
            if (!exists(directory + "/" + candidate)) return candidate;
        }
        return fileName;
    }

    private static String normalize(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isEmpty() ? "." : trimmed;
    }

    /** Kept for the settings screen's "Test connection" button. */
    public String testConnection() throws Exception {
        connect();
        String home = channel.pwd();
        return new String(("ok " + describe() + " " + home).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
