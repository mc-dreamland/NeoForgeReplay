package com.replaymod.recording.instant.mixin;

import net.minecraft.client.resource.server.ServerResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ServerResourcePackManager.class)
public interface ServerResourcePackManagerAccessor {
    /** Elements are {@code ServerResourcePackManager.PackEntry}; cast them to {@link PackEntryAccessor}. */
    @Accessor("packs")
    List<?> replaymod$getPacks();
}
