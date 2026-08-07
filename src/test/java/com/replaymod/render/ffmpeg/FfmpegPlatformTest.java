package com.replaymod.render.ffmpeg;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class FfmpegPlatformTest {
    @Test
    public void detectsWindowsX64() {
        assertEquals(FfmpegPlatform.WIN32_X64, FfmpegPlatform.forOsAndArch("Windows 11", "amd64"));
    }

    @Test
    public void detectsMacArm64() {
        assertEquals(FfmpegPlatform.DARWIN_ARM64, FfmpegPlatform.forOsAndArch("Mac OS X", "aarch64"));
    }

    @Test
    public void detectsLinuxArmAndX64() {
        assertEquals(FfmpegPlatform.LINUX_ARM, FfmpegPlatform.forOsAndArch("Linux", "armv7l"));
        assertEquals(FfmpegPlatform.LINUX_X64, FfmpegPlatform.forOsAndArch("Linux", "x86_64"));
    }

    @Test
    public void rejectsPlatformsWithoutABuild() {
        expectUnsupported("Windows", "aarch64");
        expectUnsupported("FreeBSD", "amd64");
    }

    @Test
    public void exposesExecutableAndMirrorResourceNames() {
        assertEquals("ffmpeg.exe", FfmpegPlatform.WIN32_X64.executableName());
        assertEquals("ffmpeg-win32-x64.gz", FfmpegPlatform.WIN32_X64.compressedResource());
        assertEquals("win32-x64.LICENSE", FfmpegPlatform.WIN32_X64.licenseResource());
        assertEquals("ffmpeg", FfmpegPlatform.DARWIN_ARM64.executableName());
        assertEquals("ffmpeg-darwin-arm64.gz", FfmpegPlatform.DARWIN_ARM64.compressedResource());
    }

    private static void expectUnsupported(String os, String arch) {
        try {
            FfmpegPlatform.forOsAndArch(os, arch);
            fail("Expected unsupported platform for " + os + "/" + arch);
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
