package com.replaymod.render.gui;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.versions.MCVer;
import com.replaymod.render.ffmpeg.FfmpegDownloader;
import com.replaymod.render.ffmpeg.FfmpegInstaller;
import com.replaymod.render.ffmpeg.FfmpegPayload;
import com.replaymod.render.ffmpeg.FfmpegPlatform;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.container.GuiScreen;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.advanced.GuiProgressBar;
import de.johni0702.minecraft.gui.function.Tickable;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.layout.VerticalLayout;
import net.minecraft.client.resource.language.I18n;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.replaymod.core.versions.MCVer.openURL;
import static com.replaymod.render.ReplayModRender.LOGGER;
import static de.johni0702.minecraft.gui.versions.MCVer.setClipboardString;

public class GuiNoFfmpeg extends GuiScreen implements Tickable {

    private static final String LINK = "https://www.replaymod.com/docs/#installing-ffmpeg";

    private final Runnable goBack;
    private final GuiLabel message = new GuiLabel()
            .setI18nText("replaymod.gui.rendering.error.message");
    private final GuiLabel status = new GuiLabel()
            .setI18nText("replaymod.gui.ffmpeg.status.idle");
    private final GuiProgressBar progressBar = new GuiProgressBar()
            .setSize(220, 20)
            .setProgress(0f)
            .setI18nLabel("replaymod.gui.ffmpeg.status.idle");
    private final GuiButton downloadButton = new GuiButton()
            .setI18nLabel("replaymod.gui.ffmpeg.download")
            .setSize(160, 20)
            .onClick(this::onDownloadButtonClick);
    private final GuiLabel link = new GuiLabel()
            .setText(LINK);
    private final GuiButton openLinkButton = new GuiButton()
            .setI18nLabel("chat.link.open")
            .setSize(100, 20)
            .onClick(() -> openURL(URI.create(LINK)));
    private final GuiButton copyToClipboardButton = new GuiButton()
            .setI18nLabel("chat.copy")
            .setSize(100, 20)
            .onClick(() -> setClipboardString(LINK));
    private final GuiButton backButton = new GuiButton()
            .setI18nLabel("gui.back")
            .setSize(100, 20)
            .onClick(goBackRunnable());
    private final GuiPanel buttons = new GuiPanel()
            .setLayout(new HorizontalLayout(HorizontalLayout.Alignment.CENTER).setSpacing(4))
            .addElements(null, openLinkButton, copyToClipboardButton, backButton);

    private volatile Phase phase = Phase.IDLE;
    private volatile String statusKey = "replaymod.gui.ffmpeg.status.idle";
    private volatile long downloadedBytes;
    private volatile long totalBytes = -1L;
    private volatile long expectedBytes = -1L;
    private volatile boolean busy;
    private boolean progressBarAdded;

    {
        setBackground(Background.DIRT);
        setTitle(new GuiLabel().setI18nText("replaymod.gui.rendering.error.title"));
        setLayout(new VerticalLayout(VerticalLayout.Alignment.CENTER).setSpacing(8));
        addElements(
                new VerticalLayout.Data(0.5),
                message,
                status,
                downloadButton,
                link,
                buttons
        );
    }

    public GuiNoFfmpeg(Runnable goBack) {
        this.goBack = goBack;
        try {
            FfmpegPlatform.detect();
        } catch (UnsupportedOperationException exception) {
            LOGGER.warn("No FFmpeg download is available for this platform", exception);
            phase = Phase.ERROR;
            statusKey = "replaymod.gui.ffmpeg.status.unsupported";
            downloadButton.setEnabled(false);
        }
    }

    private void onDownloadButtonClick() {
        if (phase == Phase.DONE) {
            goBack.run();
            return;
        }
        if (busy) {
            return;
        }

        busy = true;
        phase = Phase.DOWNLOADING;
        statusKey = "replaymod.gui.ffmpeg.status.downloading";
        downloadedBytes = 0L;
        totalBytes = -1L;
        if (!progressBarAdded) {
            addElements(new VerticalLayout.Data(0.5, false), progressBar);
            progressBarAdded = true;
        }
        downloadButton.setEnabled(false);
        downloadButton.setI18nLabel("replaymod.gui.ffmpeg.downloading.button");
        Thread thread = new Thread(this::downloadAndInstall, "ReplayMod FFmpeg Downloader");
        thread.setDaemon(true);
        thread.start();
    }

    private void downloadAndInstall() {
        Path temporaryDirectory = null;
        try {
            FfmpegPayload payload = new FfmpegPayload(FfmpegPlatform.detect());
            expectedBytes = payload.compressedSize();
            File runDirectory = MCVer.getMinecraft().runDirectory;
            if (runDirectory == null) {
                throw new IllegalStateException("Minecraft run directory is not available");
            }

            FfmpegDownloader downloader = new FfmpegDownloader(payload);
            Path ffmpegDirectory = runDirectory.toPath().resolve("ffmpeg");
            Files.createDirectories(ffmpegDirectory);
            temporaryDirectory = Files.createTempDirectory(ffmpegDirectory, "replaymod-ffmpeg-");
            Path compressed = temporaryDirectory.resolve("ffmpeg.gz");
            downloader.downloadCompressed(compressed, (downloaded, total) -> {
                downloadedBytes = downloaded;
                totalBytes = total;
                scheduleRefresh();
            });

            phase = Phase.INSTALLING;
            statusKey = "replaymod.gui.ffmpeg.status.installing";
            scheduleRefresh();
            Path executable = temporaryDirectory.resolve(payload.executableName());
            downloader.decompress(compressed, executable);
            byte[] license = downloader.downloadText(payload.licenseUrl());
            byte[] readme = downloader.downloadText(payload.readmeUrl());

            FfmpegInstaller installer = new FfmpegInstaller(runDirectory.toPath(), payload);
            FfmpegInstaller.InstallResult result = installer.install(executable, license, readme);
            LOGGER.info("FFmpeg install result={} executable={}", result, installer.executable());

            phase = Phase.DONE;
            if (result == FfmpegInstaller.InstallResult.PRESERVED_USER_INSTALL
                    || result == FfmpegInstaller.InstallResult.PRESERVED_MODIFIED_INSTALL) {
                statusKey = "replaymod.gui.ffmpeg.status.exists";
            } else {
                statusKey = "replaymod.gui.ffmpeg.status.done";
            }
        } catch (Throwable throwable) {
            LOGGER.error("Unable to download and install FFmpeg", throwable);
            phase = Phase.ERROR;
            statusKey = "replaymod.gui.ffmpeg.status.error";
        } finally {
            if (temporaryDirectory != null) {
                FileUtils.deleteQuietly(temporaryDirectory.toFile());
            }
            busy = false;
            scheduleRefresh();
        }
    }

    private void scheduleRefresh() {
        if (ReplayMod.instance != null) {
            ReplayMod.instance.runLaterWithoutLock(this::refreshFromState);
        }
    }

    private Runnable goBackRunnable() {
        return () -> {
            if (!busy) {
                goBack.run();
            }
        };
    }

    @Override
    public void tick() {
        refreshFromState();
    }

    private void refreshFromState() {
        if (phase == Phase.DOWNLOADING) {
            long total = totalBytes > 0L ? totalBytes : expectedBytes;
            if (total > 0L) {
                progressBar.setProgress((float) downloadedBytes / total);
                int percent = (int) (downloadedBytes * 100 / total);
                String prefix = I18n.translate("replaymod.gui.ffmpeg.downloading").replace("%", "%%");
                progressBar.setLabel(
                        prefix + " " + percent + "%%"
                );
            } else {
                progressBar.setProgress(0f);
                progressBar.setI18nLabel("replaymod.gui.ffmpeg.downloading.unknown");
            }
        } else if (phase == Phase.INSTALLING) {
            progressBar.setProgress(0f);
            progressBar.setI18nLabel("replaymod.gui.ffmpeg.installing");
        } else if (phase == Phase.DONE) {
            progressBar.setProgress(1f);
            progressBar.setI18nLabel("replaymod.gui.ffmpeg.done");
        } else if (phase == Phase.ERROR) {
            progressBar.setProgress(0f);
            progressBar.setI18nLabel("replaymod.gui.ffmpeg.error");
        }
        if ((phase == Phase.DONE || phase == Phase.ERROR) && progressBarAdded) {
            removeElement(progressBar);
            progressBarAdded = false;
        }
        status.setI18nText(statusKey);

        if (phase == Phase.DONE) {
            downloadButton.setEnabled(true);
            downloadButton.setI18nLabel("replaymod.gui.ffmpeg.done");
        } else if (phase == Phase.ERROR && !statusKey.equals("replaymod.gui.ffmpeg.status.unsupported")) {
            downloadButton.setEnabled(true);
            downloadButton.setI18nLabel("replaymod.gui.ffmpeg.download");
        } else if (phase == Phase.ERROR) {
            downloadButton.setEnabled(false);
        } else {
            downloadButton.setEnabled(!busy);
            if (!busy) {
                downloadButton.setI18nLabel("replaymod.gui.ffmpeg.download");
            }
        }
    }

    private enum Phase {
        IDLE,
        DOWNLOADING,
        INSTALLING,
        DONE,
        ERROR
    }
}
