package com.replaymod.replay;

import com.replaymod.replaystudio.replay.ReplayFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

final class ReplayResourcePackResolver implements AutoCloseable {
    interface ContentSource {
        Map<Integer, String> index() throws IOException;

        Optional<InputStream> open(String contentSha1) throws IOException;
    }

    private final ContentSource source;
    private final Path extractionDirectory;

    ReplayResourcePackResolver(ReplayFile replayFile) throws IOException {
        this(new ContentSource() {
            @Override
            public Map<Integer, String> index() throws IOException {
                return replayFile.getResourcePackIndex();
            }

            @Override
            public Optional<InputStream> open(String contentSha1) throws IOException {
                com.replaymod.replaystudio.lib.guava.base.Optional<InputStream> entry =
                        replayFile.getResourcePack(contentSha1);
                return entry.isPresent() ? Optional.of(entry.get()) : Optional.empty();
            }
        }, Files.createTempDirectory("replaymod-resource-packs-"));
    }

    ReplayResourcePackResolver(ContentSource source, Path extractionDirectory) throws IOException {
        this.source = source;
        this.extractionDirectory = extractionDirectory;
        Files.createDirectories(extractionDirectory);
    }

    PackDescriptor resolve(UUID id, int requestId) throws IOException {
        Map<Integer, String> index = source.index();
        if (index == null) {
            throw new IOException("missing-index");
        }

        String expectedSha1 = normalizeSha1(index.get(requestId));
        if (expectedSha1 == null) {
            throw new IOException("missing-index-entry");
        }

        Path target = extractionDirectory.resolve(expectedSha1 + ".zip");
        if (!Files.isRegularFile(target)) {
            extractVerified(expectedSha1, target);
        }
        return new PackDescriptor(id, expectedSha1, target);
    }

    private void extractVerified(String expectedSha1, Path target) throws IOException {
        Optional<InputStream> replayEntry = source.open(expectedSha1);
        if (!replayEntry.isPresent()) {
            throw new IOException("missing-replay-entry");
        }

        Path partial = extractionDirectory.resolve(expectedSha1 + ".part");
        Files.deleteIfExists(partial);
        MessageDigest digest = sha1();
        try (InputStream input = replayEntry.get();
             OutputStream output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Throwable throwable) {
            Files.deleteIfExists(partial);
            throw throwable;
        }

        String actualSha1 = toHex(digest.digest());
        if (!expectedSha1.equals(actualSha1)) {
            Files.deleteIfExists(partial);
            throw new IOException("sha1-mismatch");
        }

        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.deleteIfExists(partial);
            throw new IOException("atomic-move-unsupported", exception);
        }
    }

    private static String normalizeSha1(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{40}") ? normalized : null;
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-1 is required by the Java runtime", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(extractionDirectory)) return;
        try (Stream<Path> paths = Files.walk(extractionDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new DeleteFailure(exception);
                }
            });
        } catch (DeleteFailure failure) {
            throw failure.cause;
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private final IOException cause;

        private DeleteFailure(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
