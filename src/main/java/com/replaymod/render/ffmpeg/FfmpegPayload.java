package com.replaymod.render.ffmpeg;

/**
 * Pinned FFmpeg payload. The binary is downloaded on demand from npmmirror's
 * ffmpeg-static mirror (Aliyun OSS backed) instead of being bundled into the mod jar.
 */
public final class FfmpegPayload {
    static final String VERSION = "6.1.1-essentials";
    static final String BASE_URL =
            "https://cdn.npmmirror.com/binaries/ffmpeg-static/b6.1.1";
    static final String SOURCE_URL =
            "https://github.com/FFmpeg/FFmpeg/commit/e38092ef93";

    private final FfmpegPlatform platform;
    private final String version;
    private final String downloadUrl;
    private final String licenseUrl;
    private final String readmeUrl;
    private final String sourceUrl;
    private final long compressedSize;
    private final String compressedSha256;
    private final long binarySize;
    private final String binarySha256;

    public FfmpegPayload(FfmpegPlatform platform) {
        this(
                platform,
                VERSION,
                BASE_URL + "/" + platform.compressedResource(),
                BASE_URL + "/" + platform.licenseResource(),
                BASE_URL + "/" + platform.readmeResource(),
                SOURCE_URL,
                platform.compressedSize(),
                platform.compressedSha256(),
                platform.binarySize(),
                platform.binarySha256()
        );
    }

    FfmpegPayload(
            FfmpegPlatform platform,
            String version,
            String downloadUrl,
            String licenseUrl,
            String readmeUrl,
            String sourceUrl,
            long compressedSize,
            String compressedSha256,
            long binarySize,
            String binarySha256
    ) {
        this.platform = platform;
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.licenseUrl = licenseUrl;
        this.readmeUrl = readmeUrl;
        this.sourceUrl = sourceUrl;
        this.compressedSize = compressedSize;
        this.compressedSha256 = compressedSha256;
        this.binarySize = binarySize;
        this.binarySha256 = binarySha256;
    }

    FfmpegPlatform platform() {
        return platform;
    }

    public String version() {
        return version;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    public String licenseUrl() {
        return licenseUrl;
    }

    public String readmeUrl() {
        return readmeUrl;
    }

    String sourceUrl() {
        return sourceUrl;
    }

    public long compressedSize() {
        return compressedSize;
    }

    String compressedSha256() {
        return compressedSha256;
    }

    long binarySize() {
        return binarySize;
    }

    String binarySha256() {
        return binarySha256;
    }

    public String executableName() {
        return platform.executableName();
    }
}
