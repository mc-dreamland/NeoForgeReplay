package com.replaymod.replay;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReplayResourcePackResolverTest {
    private static final byte[] CONTENT = "replay-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String CONTENT_SHA1 = "04c75a68e315429c9d1ddd9e8ae4c882c09db4dd";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractsVerifiedPackAndRemovesPartFile() throws Exception {
        ReplayResourcePackResolver resolver = resolver(CONTENT_SHA1, CONTENT);
        PackDescriptor descriptor = resolver.resolve(UUID.randomUUID(), 7);

        assertTrue(Files.isRegularFile(descriptor.extractedPath()));
        assertArrayEquals(CONTENT, Files.readAllBytes(descriptor.extractedPath()));
        assertFalse(Files.exists(descriptor.extractedPath().resolveSibling(CONTENT_SHA1 + ".part")));
        resolver.close();
    }

    @Test
    public void rejectsMissingReplayEntry() throws Exception {
        ReplayResourcePackResolver resolver = new ReplayResourcePackResolver(source(CONTENT_SHA1, null),
                temporaryFolder.newFolder().toPath());
        assertFailureReason(resolver, "missing-replay-entry");
    }

    @Test
    public void rejectsHashMismatchAndDeletesPartFile() throws Exception {
        ReplayResourcePackResolver resolver = resolver("0000000000000000000000000000000000000000", CONTENT);
        assertFailureReason(resolver, "sha1-mismatch");
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(temporaryFolder.getRoot().toPath())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private ReplayResourcePackResolver resolver(String hash, byte[] content) throws IOException {
        return new ReplayResourcePackResolver(source(hash, content), temporaryFolder.newFolder().toPath());
    }

    private static ReplayResourcePackResolver.ContentSource source(String hash, byte[] content) {
        return new ReplayResourcePackResolver.ContentSource() {
            @Override
            public Map<Integer, String> index() {
                return Collections.singletonMap(7, hash);
            }

            @Override
            public Optional<InputStream> open(String contentSha1) {
                return content == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(content));
            }
        };
    }

    private static void assertFailureReason(ReplayResourcePackResolver resolver, String reason) throws Exception {
        try {
            resolver.resolve(UUID.randomUUID(), 7);
            fail("Expected resolver failure");
        } catch (IOException exception) {
            org.junit.Assert.assertEquals(reason, exception.getMessage());
        } finally {
            resolver.close();
        }
    }
}
