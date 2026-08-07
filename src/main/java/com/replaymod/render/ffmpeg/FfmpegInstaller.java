package com.replaymod.render.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.UUID;

/**
 * Installs the downloaded FFmpeg executable into the game directory while
 * preserving existing player-managed installations.
 */
public final class FfmpegInstaller {
    private static final String MANAGED_BY = "replaymod-ffmpeg-runtime";

    private final Path gameDirectory;
    private final FfmpegPayload payload;
    private final Path installDirectory;
    private final Path executable;
    private final Path marker;
    private final Path lockFile;

    public FfmpegInstaller(Path gameDirectory, FfmpegPayload payload) {
        this.gameDirectory = gameDirectory;
        this.payload = payload;
        this.installDirectory = gameDirectory.resolve("ffmpeg");
        this.executable = installDirectory.resolve("bin").resolve(payload.executableName());
        this.marker = installDirectory.resolve(".replaymod-managed.properties");
        this.lockFile = installDirectory.resolve(".replaymod-install.lock");
    }

    public Path executable() {
        return executable;
    }

    public InstallResult install(Path downloadedExecutable, byte[] license, byte[] readme) throws IOException {
        verifyPayload(downloadedExecutable);
        Files.createDirectories(executable.getParent());
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            ManagedState state = readManagedState();
            if (Files.isRegularFile(executable)) {
                if (state == null) {
                    if (matchesPayload(executable)) {
                        writeSupportingFiles(license, readme);
                        writeMarker();
                        return InstallResult.ADOPTED_CURRENT;
                    }
                    return InstallResult.PRESERVED_USER_INSTALL;
                }
                if (!state.isOwnedByRuntime()) {
                    return InstallResult.PRESERVED_USER_INSTALL;
                }
                if (state.matches(payload) && Files.size(executable) == payload.binarySize()) {
                    return InstallResult.CURRENT;
                }
                if (!state.matchesFile(executable)) {
                    return InstallResult.PRESERVED_MODIFIED_INSTALL;
                }
            }

            installPayload(downloadedExecutable, license, readme);
            return state == null ? InstallResult.INSTALLED : InstallResult.UPDATED;
        }
    }

    private void installPayload(Path downloadedExecutable, byte[] license, byte[] readme) throws IOException {
        Path temporary = executable.resolveSibling(executable.getFileName() + ".installing-" + UUID.randomUUID());
        try {
            copyAtomically(downloadedExecutable, temporary);
            writeSupportingFiles(license, readme);
            moveAtomically(temporary, executable);
            executable.toFile().setExecutable(true, false);
            writeMarker();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void verifyPayload(Path file) throws IOException {
        if (!Files.isRegularFile(file)
                || Files.size(file) != payload.binarySize()
                || !payload.binarySha256().equals(sha256(file))) {
            throw new IOException("Downloaded FFmpeg executable failed its integrity check");
        }
    }

    private boolean matchesPayload(Path file) throws IOException {
        return Files.size(file) == payload.binarySize()
                && payload.binarySha256().equals(sha256(file));
    }

    private void writeSupportingFiles(byte[] license, byte[] readme) throws IOException {
        writeAtomically(license, installDirectory.resolve("LICENSE.ffmpeg.txt"));
        writeAtomically(readme, installDirectory.resolve("README.ffmpeg.txt"));
    }

    private void writeAtomically(byte[] bytes, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".installing-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeMarker() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", "1");
        properties.setProperty("managedBy", MANAGED_BY);
        properties.setProperty("version", payload.version());
        properties.setProperty("binarySha256", payload.binarySha256());
        properties.setProperty("binarySize", Long.toString(payload.binarySize()));
        properties.setProperty("downloadUrl", payload.downloadUrl());
        properties.setProperty("sourceUrl", payload.sourceUrl());

        Path temporary = marker.resolveSibling(marker.getFileName() + ".installing-" + UUID.randomUUID());
        try (OutputStream output = Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            properties.store(output, "Managed by ReplayMod; do not edit while Minecraft is running.");
        }
        try {
            moveAtomically(temporary, marker);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ManagedState readManagedState() {
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(marker)) {
            properties.load(input);
            if (!"1".equals(properties.getProperty("schemaVersion"))) {
                return null;
            }
            return new ManagedState(
                    properties.getProperty("managedBy", ""),
                    properties.getProperty("version", ""),
                    properties.getProperty("binarySha256", ""),
                    Long.parseLong(properties.getProperty("binarySize", "-1"))
            );
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private static void copyAtomically(Path source, Path target) throws IOException {
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IOException("Unable to stage FFmpeg executable", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    public enum InstallResult {
        CURRENT,
        INSTALLED,
        UPDATED,
        ADOPTED_CURRENT,
        PRESERVED_USER_INSTALL,
        PRESERVED_MODIFIED_INSTALL
    }

    private static final class ManagedState {
        private final String managedBy;
        private final String version;
        private final String binarySha256;
        private final long binarySize;

        private ManagedState(String managedBy, String version, String binarySha256, long binarySize) {
            this.managedBy = managedBy;
            this.version = version;
            this.binarySha256 = binarySha256;
            this.binarySize = binarySize;
        }

        boolean isOwnedByRuntime() {
            return MANAGED_BY.equals(managedBy);
        }

        boolean matches(FfmpegPayload payload) {
            return version.equals(payload.version())
                    && binarySha256.equals(payload.binarySha256())
                    && binarySize == payload.binarySize();
        }

        boolean matchesFile(Path file) throws IOException {
            return Files.size(file) == binarySize && binarySha256.equals(sha256(file));
        }
    }
}
