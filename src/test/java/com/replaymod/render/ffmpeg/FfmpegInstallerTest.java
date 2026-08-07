package com.replaymod.render.ffmpeg;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class FfmpegInstallerTest {
    private static final byte[] LICENSE = "license text".getBytes();
    private static final byte[] README = "readme text".getBytes();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsAndThenReusesCurrentPayload() throws IOException {
        byte[] binary = "ffmpeg-v1".getBytes();
        FfmpegInstaller installer = installer("1.0", binary);

        assertEquals(FfmpegInstaller.InstallResult.INSTALLED, installer.install(stage(binary), LICENSE, README));
        assertArrayEquals(binary, Files.readAllBytes(installer.executable()));
        assertTrue(Files.isRegularFile(gameDirectory().resolve("ffmpeg/LICENSE.ffmpeg.txt")));
        assertTrue(Files.isRegularFile(gameDirectory().resolve("ffmpeg/README.ffmpeg.txt")));
        assertEquals(FfmpegInstaller.InstallResult.CURRENT, installer.install(stage(binary), LICENSE, README));
    }

    @Test
    public void preservesAnExistingUserInstallation() throws IOException {
        Path executable = gameDirectory().resolve("ffmpeg/bin/ffmpeg.exe");
        Files.createDirectories(executable.getParent());
        Files.write(executable, "player-owned".getBytes());

        assertEquals(
                FfmpegInstaller.InstallResult.PRESERVED_USER_INSTALL,
                installer("1.0", "bundled".getBytes()).install(stage("bundled".getBytes()), LICENSE, README)
        );
        assertEquals("player-owned", new String(Files.readAllBytes(executable)));
        assertFalse(Files.exists(gameDirectory().resolve("ffmpeg/.replaymod-managed.properties")));
    }

    @Test
    public void updatesOnlyAnUnmodifiedManagedInstallation() throws IOException {
        FfmpegInstaller first = installer("1.0", "first".getBytes());
        assertEquals(
                FfmpegInstaller.InstallResult.INSTALLED,
                first.install(stage("first".getBytes()), LICENSE, README)
        );

        FfmpegInstaller second = installer("2.0", "second".getBytes());
        assertEquals(
                FfmpegInstaller.InstallResult.UPDATED,
                second.install(stage("second".getBytes()), LICENSE, README)
        );
        assertEquals("second", new String(Files.readAllBytes(second.executable())));

        Files.write(second.executable(), "player replacement".getBytes());
        FfmpegInstaller third = installer("3.0", "third".getBytes());
        assertEquals(
                FfmpegInstaller.InstallResult.PRESERVED_MODIFIED_INSTALL,
                third.install(stage("third".getBytes()), LICENSE, README)
        );
        assertEquals("player replacement", new String(Files.readAllBytes(third.executable())));
    }

    @Test
    public void rejectsACorruptDownloadWithoutPublishingIt() throws IOException {
        byte[] binary = "payload".getBytes();
        FfmpegPayload payload = new FfmpegPayload(
                FfmpegPlatform.WIN32_X64,
                "1.0",
                "https://example.invalid/ffmpeg.gz",
                "https://example.invalid/LICENSE",
                "https://example.invalid/README",
                "https://example.invalid/source",
                binary.length,
                sha256(binary),
                binary.length,
                sha256("different".getBytes())
        );
        FfmpegInstaller installer = new FfmpegInstaller(gameDirectory(), payload);

        try {
            installer.install(stage(binary), LICENSE, README);
            fail("Expected corrupt payload to be rejected");
        } catch (IOException expected) {
            // expected
        }
        assertFalse(Files.exists(installer.executable()));
    }

    private FfmpegInstaller installer(String version, byte[] binary) {
        return new FfmpegInstaller(gameDirectory(), payload(version, binary));
    }

    private static FfmpegPayload payload(String version, byte[] binary) {
        return new FfmpegPayload(
                FfmpegPlatform.WIN32_X64,
                version,
                "https://example.invalid/ffmpeg.gz",
                "https://example.invalid/LICENSE",
                "https://example.invalid/README",
                "https://example.invalid/source",
                binary.length,
                sha256(binary),
                binary.length,
                sha256(binary)
        );
    }

    private Path stage(byte[] binary) throws IOException {
        Path staged = gameDirectory().resolve("staged-ffmpeg.exe");
        Files.write(staged, binary);
        return staged;
    }

    private Path gameDirectory() {
        return temporaryFolder.getRoot().toPath();
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
