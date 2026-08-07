package com.replaymod.render.ffmpeg;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the pinned FFmpeg archive and verifies both the gzip transport file
 * and the decompressed executable against their SHA-256 hashes.
 */
public final class FfmpegDownloader {
    public interface ProgressListener {
        void onProgress(long downloaded, long total) throws IOException;
    }

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final FfmpegPayload payload;

    public FfmpegDownloader(FfmpegPayload payload) {
        this.payload = payload;
    }

    public void downloadCompressed(Path target, ProgressListener listener) throws IOException {
        download(target, payload.downloadUrl(), payload.compressedSize(), listener);
        verify(target, payload.compressedSize(), payload.compressedSha256());
    }

    public void decompress(Path compressed, Path target) throws IOException {
        MessageDigest digest = sha256();
        long size = 0L;
        try (InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(compressed), BUFFER_SIZE));
             OutputStream output = new BufferedOutputStream(
                     Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                     BUFFER_SIZE
             )) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        if (size != payload.binarySize() || !payload.binarySha256().equals(toHex(digest.digest()))) {
            throw new IOException("Decompressed FFmpeg executable failed its integrity check");
        }
        target.toFile().setExecutable(true, false);
    }

    public byte[] downloadText(String url) throws IOException {
        Path temporary = Files.createTempFile("replaymod-ffmpeg-", ".txt");
        try {
            download(temporary, url, -1L, null);
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void download(Path target, String url, long expectedSize, ProgressListener listener) throws IOException {
        Files.deleteIfExists(target);
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "ReplayMod/2.6 FFmpeg Downloader");
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection;
            int responseCode = http.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " while downloading " + url);
            }
        }
        long total = connection.getContentLengthLong();
        if (total <= 0 && expectedSize >= 0) {
            // Some proxies/CDNs omit Content-Length; fall back to the pinned size.
            total = expectedSize;
        }
        if (expectedSize >= 0 && total >= 0 && total != expectedSize) {
            throw new IOException("Unexpected FFmpeg download size: " + total + " != " + expectedSize);
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(
                     Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                     BUFFER_SIZE
             )) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long downloaded = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                downloaded += read;
                if (listener != null) {
                    listener.onProgress(downloaded, total);
                }
            }
            if (expectedSize >= 0 && downloaded != expectedSize) {
                throw new IOException("FFmpeg download was truncated: " + downloaded + " != " + expectedSize);
            }
        }
    }

    private static void verify(Path file, long expectedSize, String expectedSha256) throws IOException {
        MessageDigest digest = sha256();
        long size = 0L;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
        }
        if (size != expectedSize || !expectedSha256.equals(toHex(digest.digest()))) {
            throw new IOException("Downloaded FFmpeg archive failed its integrity check");
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }
}
