package com.replaymod.render.ffmpeg;

import java.util.Locale;

/**
 * FFmpeg build platforms provided by the pinned ffmpeg-static 6.1.1 release.
 */
public enum FfmpegPlatform {
    WIN32_X64(
            "win32-x64",
            "ffmpeg.exe",
            29581307L,
            "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77",
            82797568L,
            "04e1307997530f9cf2fe35cba2ca7e8875ca91da02f89d6c7243df819c94ad00"
    ),
    LINUX_X64(
            "linux-x64",
            "ffmpeg",
            29354986L,
            "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa",
            79826272L,
            "e7e7fb30477f717e6f55f9180a70386c62677ef8a4d4d1a5d948f4098aa3eb99"
    ),
    LINUX_ARM64(
            "linux-arm64",
            "ffmpeg",
            25568691L,
            "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0",
            51134160L,
            "6bb182d0d75d23028db82e9e4f723ca69b853d055698486e6984ddb2c06fb8ce"
    ),
    LINUX_ARM(
            "linux-arm",
            "ffmpeg",
            19167563L,
            "64b115a12f0ab77c277e3c418aae8b40ef881e75e746a0e2d066a206b9bc5172",
            31793580L,
            "0afba4a11110e6e402053e0fc14c33a7eb207d7a588688ae87dba471a0f06c71"
    ),
    LINUX_IA32(
            "linux-ia32",
            "ffmpeg",
            22184031L,
            "169b27c078a8ecedb814cac67afccf15a9868d63e9d74ef86088adefaa500d00",
            52155436L,
            "c6472eb993612db72ca50893a34137ba11173e60a1a4c028d4660a3f755d2490"
    ),
    DARWIN_X64(
            "darwin-x64",
            "ffmpeg",
            25296431L,
            "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106",
            78862176L,
            "ebdddc936f61e14049a2d4b549a412b8a40deeff6540e58a9f2a2da9e6b18894"
    ),
    DARWIN_ARM64(
            "darwin-arm64",
            "ffmpeg",
            19246198L,
            "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa",
            45568216L,
            "a90e3db6a3fd35f6074b013f948b1aa45b31c6375489d39e572bea3f18336584"
    );

    private final String id;
    private final String executableName;
    private final long compressedSize;
    private final String compressedSha256;
    private final long binarySize;
    private final String binarySha256;

    FfmpegPlatform(
            String id,
            String executableName,
            long compressedSize,
            String compressedSha256,
            long binarySize,
            String binarySha256
    ) {
        this.id = id;
        this.executableName = executableName;
        this.compressedSize = compressedSize;
        this.compressedSha256 = compressedSha256;
        this.binarySize = binarySize;
        this.binarySha256 = binarySha256;
    }

    String id() {
        return id;
    }

    public String executableName() {
        return executableName;
    }

    String compressedResource() {
        return "ffmpeg-" + id + ".gz";
    }

    String licenseResource() {
        return id + ".LICENSE";
    }

    String readmeResource() {
        return id + ".README";
    }

    long compressedSize() {
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

    public static FfmpegPlatform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return forOsAndArch(os, arch);
    }

    static FfmpegPlatform forOsAndArch(String os, String arch) {
        String normalizedOs = os.toLowerCase(Locale.ROOT);
        boolean windows = normalizedOs.contains("win");
        boolean mac = normalizedOs.contains("mac") || normalizedOs.contains("darwin");
        boolean linux = normalizedOs.contains("linux") || normalizedOs.contains("nux");

        String normalizedArch = normalizeArch(arch.toLowerCase(Locale.ROOT));
        if (windows) {
            if ("x64".equals(normalizedArch)) {
                return WIN32_X64;
            }
            throw unsupported(os, arch);
        }
        if (mac) {
            if ("x64".equals(normalizedArch)) {
                return DARWIN_X64;
            }
            if ("arm64".equals(normalizedArch)) {
                return DARWIN_ARM64;
            }
            throw unsupported(os, arch);
        }
        if (linux) {
            if ("x64".equals(normalizedArch)) {
                return LINUX_X64;
            }
            if ("arm64".equals(normalizedArch)) {
                return LINUX_ARM64;
            }
            if ("arm".equals(normalizedArch)) {
                return LINUX_ARM;
            }
            if ("ia32".equals(normalizedArch)) {
                return LINUX_IA32;
            }
            throw unsupported(os, arch);
        }
        throw unsupported(os, arch);
    }

    private static String normalizeArch(String arch) {
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x64":
                return "x64";
            case "aarch64":
            case "arm64":
                return "arm64";
            case "arm":
            case "armv7":
            case "armv7l":
            case "armhf":
                return "arm";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "ia32";
            default:
                return arch;
        }
    }

    private static UnsupportedOperationException unsupported(String os, String arch) {
        return new UnsupportedOperationException(
                "No bundled FFmpeg build is available for " + os + "/" + arch
        );
    }
}
