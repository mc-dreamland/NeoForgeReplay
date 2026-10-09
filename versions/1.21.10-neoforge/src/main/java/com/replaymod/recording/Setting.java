package com.replaymod.recording;

import com.replaymod.core.SettingsRegistry;

public final class Setting<T> extends SettingsRegistry.SettingKeys<T> {
    public static final Setting<Boolean> RECORD_SINGLEPLAYER = make("recordSingleplayer", "recordsingleplayer", true);
    public static final Setting<Boolean> RECORD_SERVER = make("recordServer", "recordserver", false);
    public static final Setting<Boolean> INDICATOR = make("indicator", "indicator", true);
    public static final Setting<Boolean> AUTO_START_RECORDING = make("autoStartRecording", "autostartrecording", false);
    public static final Setting<Boolean> AUTO_POST_PROCESS = make("autoPostProcess", null, true);
    public static final Setting<Boolean> RENAME_DIALOG = make("renameDialog", "rename_recording_dialog", true);
    /** Whether instant recordings start with the local player's inventory, experience and health. */
    public static final Setting<Boolean> RECORD_PLAYER_STATUS = make("recordPlayerStatus", "recordplayerstatus", true);

    private static <T> Setting<T> make(String key, String displayName, T defaultValue) {
        return new Setting<>(key, displayName, defaultValue);
    }

    public Setting(String key, String displayString, T defaultValue) {
        super("recording", key, displayString == null ? null : "replaymod.gui.settings." + displayString,
                defaultValue);
    }
}
