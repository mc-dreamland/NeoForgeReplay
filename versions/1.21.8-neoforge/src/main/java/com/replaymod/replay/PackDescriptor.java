package com.replaymod.replay;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

final class PackDescriptor {
    private final UUID id;
    private final String contentSha1;
    private final Path extractedPath;

    PackDescriptor(UUID id, String contentSha1, Path extractedPath) {
        this.id = Objects.requireNonNull(id, "id");
        this.contentSha1 = Objects.requireNonNull(contentSha1, "contentSha1");
        this.extractedPath = Objects.requireNonNull(extractedPath, "extractedPath");
    }

    UUID id() {
        return id;
    }

    String contentSha1() {
        return contentSha1;
    }

    Path extractedPath() {
        return extractedPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PackDescriptor)) return false;
        PackDescriptor that = (PackDescriptor) other;
        return id.equals(that.id) && contentSha1.equals(that.contentSha1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, contentSha1);
    }
}
