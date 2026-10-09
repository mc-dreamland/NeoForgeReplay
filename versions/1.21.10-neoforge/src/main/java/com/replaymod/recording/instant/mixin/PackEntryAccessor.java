package com.replaymod.recording.instant.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;
import java.util.UUID;

@Mixin(targets = "net.minecraft.client.resource.server.ServerResourcePackManager$PackEntry")
public interface PackEntryAccessor {
    @Accessor("id")
    UUID replaymod$getId();

    /** Location of the downloaded pack, {@code null} until the download finished. */
    @Accessor("path")
    Path replaymod$getPath();

    @Invoker("isDiscarded")
    boolean replaymod$isDiscarded();
}
