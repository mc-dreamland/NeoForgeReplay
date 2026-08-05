package com.replaymod.recording.packet;

import com.replaymod.replaystudio.replay.ReplayMetaData;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public final class RecordingResult {
    private final List<Pair<Path, ReplayMetaData>> outputPaths;

    public RecordingResult(List<Pair<Path, ReplayMetaData>> outputPaths) {
        this.outputPaths = Collections.unmodifiableList(outputPaths);
    }

    public List<Pair<Path, ReplayMetaData>> getOutputPaths() {
        return outputPaths;
    }
}
