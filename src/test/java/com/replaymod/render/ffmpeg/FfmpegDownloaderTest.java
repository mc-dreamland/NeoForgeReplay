package com.replaymod.render.ffmpeg;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;

public class FfmpegDownloaderTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void downloadsVerifiesAndDecompressesPayload() throws IOException {
        byte[] binary = "ffmpeg-binary".getBytes();
        Path source = temporaryFolder.getRoot().toPath().resolve("source");
        Files.createDirectories(source);
        Path archive = source.resolve("ffmpeg.gz");
        byte[] compressed = gzip(binary);
        Files.write(archive, compressed);

        FfmpegPayload payload = new FfmpegPayload(
                FfmpegPlatform.LINUX_X64,
                "test",
                archive.toUri().toString(),
                archive.toUri().toString(),
                archive.toUri().toString(),
                "https://example.invalid/source",
                compressed.length,
                sha256(compressed),
                binary.length,
                sha256(binary)
        );
        FfmpegDownloader downloader = new FfmpegDownloader(payload);
        Path downloaded = temporaryFolder.getRoot().toPath().resolve("downloaded.gz");
        Path executable = temporaryFolder.getRoot().toPath().resolve("ffmpeg");

        downloader.downloadCompressed(downloaded, null);
        downloader.decompress(downloaded, executable);

        assertArrayEquals(binary, Files.readAllBytes(executable));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(Character.forDigit((value >> 4) & 0xF, 16));
                result.append(Character.forDigit(value & 0xF, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
