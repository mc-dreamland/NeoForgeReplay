package com.replaymod.recording.instant;

import com.mojang.datafixers.util.Pair;
import com.replaymod.recording.instant.mixin.BiomeAccessAccessor;
import com.replaymod.recording.instant.mixin.BossBarHudAccessor;
import com.replaymod.recording.instant.mixin.ClientWorldAccessor;
import com.replaymod.recording.instant.mixin.ClientWorldPropertiesAccessor;
import com.replaymod.recording.instant.mixin.PackEntryAccessor;
import com.replaymod.recording.instant.mixin.PlayerListHudAccessor;
import com.replaymod.recording.instant.mixin.ChunkDataS2CPacketAccessor;
import com.replaymod.recording.instant.mixin.PlayerListS2CPacketAccessor;
import com.replaymod.recording.instant.mixin.ServerResourcePackLoaderAccessor;
import com.replaymod.recording.instant.mixin.ServerResourcePackManagerAccessor;
import com.replaymod.recording.packet.ResourcePackRecorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapDecoration;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkLoadDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.network.packet.s2c.play.DifficultyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.PlayerSpawnPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SimulationDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.BitSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Rebuilds the play phase state the client currently has as server packets, so a replay can start at an arbitrary
 * point of a session. Must be called on the client thread, with all received packets applied.
 *
 * <p>The local player itself is not part of this; ReplayMod's {@link com.replaymod.recording.handler.RecordingEventHandler}
 * spawns and tracks it as for every recording.</p>
 */
final class PlayStateSnapshot {
    private static final Logger LOGGER = LogManager.getLogger("ReplayMod/InstantRecording");
    private static final int MAX_LIGHT_UPDATE_ROUNDS = 256;
    /** Largest radius of the client's chunk map: maximum view distance (32) plus its margin (3). */
    private static final int MAX_CHUNK_MAP_RADIUS = 35;

    private static final EntityTrackerEntry.TrackerPacketSender NO_OP_SENDER = new EntityTrackerEntry.TrackerPacketSender() {
        @Override
        public void sendToListeners(Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> packet) {
        }

        @Override
        public void sendToSelfAndListeners(Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> packet) {
        }

        @Override
        public void sendToListenersIf(Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> packet,
                                      Predicate<ServerPlayerEntity> predicate) {
        }
    };

    private final MinecraftClient mc;
    private final ClientWorld world;
    private final ClientPlayerEntity player;
    private final ClientPlayNetworkHandler networkHandler;
    private final ClientPlayerInteractionManager interactionManager;

    PlayStateSnapshot(MinecraftClient mc) {
        this.mc = mc;
        this.world = mc.world;
        this.player = mc.player;
        this.networkHandler = mc.getNetworkHandler();
        this.interactionManager = mc.interactionManager;
    }

    /**
     * Join and world-wide state, followed by all loaded chunks. Entities must come after this, see {@link #entities}.
     */
    void world(Consumer<Packet<?>> out) {
        ClientWorld.Properties properties = world.getLevelProperties();
        CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
                world.getDimensionEntry(),
                world.getRegistryKey(),
                ((BiomeAccessAccessor) world.getBiomeAccess()).replaymod$getSeed(),
                interactionManager.getCurrentGameMode(),
                interactionManager.getPreviousGameMode(),
                world.isDebugWorld(),
                ((ClientWorldPropertiesAccessor) properties).replaymod$isFlatWorld(),
                player.getLastDeathPos(),
                player.getPortalCooldown(),
                world.getSeaLevel());
        int viewDistance = mc.options.getClampedViewDistance();
        out.accept(new GameJoinS2CPacket(
                player.getId(),
                properties.isHardcore(),
                networkHandler.getWorldKeys(),
                0,
                viewDistance,
                world.getSimulationDistance(),
                player.hasReducedDebugInfo(),
                player.showsDeathScreen(),
                player.isLimitedCraftingEnabled(),
                spawnInfo,
                false));
        out.accept(new DifficultyS2CPacket(properties.getDifficulty(), properties.isDifficultyLocked()));
        out.accept(new PlayerAbilitiesS2CPacket(player.getAbilities()));
        out.accept(new ChunkLoadDistanceS2CPacket(viewDistance));
        out.accept(new SimulationDistanceS2CPacket(world.getSimulationDistance()));

        playerList(out);
        PlayerListHudAccessor playerListHud = (PlayerListHudAccessor) mc.inGameHud.getPlayerListHud();
        out.accept(new PlayerListHeaderS2CPacket(
                Optional.ofNullable(playerListHud.replaymod$getHeader()).orElse(Text.empty()),
                Optional.ofNullable(playerListHud.replaymod$getFooter()).orElse(Text.empty())));
        for (ClientBossBar bossBar : ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).replaymod$getBossBars().values()) {
            out.accept(BossBarS2CPacket.add(bossBar));
        }
        scoreboard(out);

        out.accept(new WorldBorderInitializeS2CPacket(world.getWorldBorder()));
        out.accept(new WorldTimeUpdateS2CPacket(world.getTime(), world.getTimeOfDay(),
                ((ClientWorldAccessor) world).replaymod$shouldTickTimeOfDay()));
        out.accept(new PlayerSpawnPositionS2CPacket(world.getSpawnPoint()));
        out.accept(new GameStateChangeS2CPacket(world.isRaining()
                ? GameStateChangeS2CPacket.RAIN_STARTED : GameStateChangeS2CPacket.RAIN_STOPPED, 0));
        out.accept(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, world.getRainGradient(1)));
        out.accept(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED,
                world.getThunderGradient(1)));

        // Ends the "loading terrain" screen, like the server's initial teleport after joining
        out.accept(PlayerPositionLookS2CPacket.of(0, EntityPosition.fromEntity(player), Set.of()));
        chunks(out);
        maps(out);
    }

    private void playerList(Consumer<Packet<?>> out) {
        // Listed entries first so their "listed" flag wins over the same player in the full list
        Map<UUID, PlayerListS2CPacket.Entry> entries = new LinkedHashMap<>();
        Set<UUID> listed = new HashSet<>();
        for (PlayerListEntry entry : networkHandler.getListedPlayerListEntries()) {
            listed.add(entry.getProfile().id());
        }
        for (PlayerListEntry entry : networkHandler.getPlayerList()) {
            UUID id = entry.getProfile().id();
            entries.put(id, new PlayerListS2CPacket.Entry(id, entry.getProfile(), listed.contains(id),
                    entry.getLatency(), entry.getGameMode(), entry.getDisplayName(), entry.shouldShowHat(),
                    entry.getListOrder(), null));
        }
        PlayerListS2CPacket packet = new PlayerListS2CPacket(EnumSet.of(
                PlayerListS2CPacket.Action.ADD_PLAYER,
                PlayerListS2CPacket.Action.UPDATE_GAME_MODE,
                PlayerListS2CPacket.Action.UPDATE_LISTED,
                PlayerListS2CPacket.Action.UPDATE_LATENCY,
                PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
                PlayerListS2CPacket.Action.UPDATE_HAT,
                PlayerListS2CPacket.Action.UPDATE_LIST_ORDER), List.of());
        ((PlayerListS2CPacketAccessor) packet).replaymod$setEntries(new ArrayList<>(entries.values()));
        out.accept(packet);
    }

    private void scoreboard(Consumer<Packet<?>> out) {
        Scoreboard scoreboard = networkHandler.getScoreboard();
        // Objectives before teams/displays/scores which refer to them
        for (ScoreboardObjective objective : scoreboard.getObjectives()) {
            out.accept(new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
        }
        for (ScoreboardDisplaySlot slot : ScoreboardDisplaySlot.values()) {
            ScoreboardObjective objective = scoreboard.getObjectiveForSlot(slot);
            if (objective != null) {
                out.accept(new ScoreboardDisplayS2CPacket(slot, objective));
            }
        }
        for (Team team : scoreboard.getTeams()) {
            out.accept(TeamS2CPacket.updateTeam(team, true));
        }
        for (ScoreboardObjective objective : scoreboard.getObjectives()) {
            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
                out.accept(new ScoreboardScoreUpdateS2CPacket(entry.owner(), objective.getName(), entry.value(),
                        Optional.ofNullable(entry.display()), Optional.ofNullable(entry.numberFormatOverride())));
            }
        }
    }

    private void chunks(Consumer<Packet<?>> out) {
        // The server keeps the client's chunk map centered on the player's chunk
        int centerX = player.getChunkPos().x;
        int centerZ = player.getChunkPos().z;
        out.accept(new ChunkRenderDistanceCenterS2CPacket(centerX, centerZ));

        // Light data is serialized from the client's light engine, so apply everything it has pending first
        world.runQueuedChunkUpdates();
        LightingProvider lighting = world.getChunkManager().getLightingProvider();
        for (int i = 0; i < MAX_LIGHT_UPDATE_ROUNDS && lighting.hasUpdates(); i++) {
            lighting.doLightUpdates();
        }

        // The client's chunk map never extends beyond the maximum view distance plus its margin; lookups outside of
        // the loaded area return null.
        int radius = MAX_CHUNK_MAP_RADIUS;
        List<WorldChunk> chunks = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (world.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false) instanceof WorldChunk chunk
                        && !(chunk instanceof EmptyChunk)) {
                    chunks.add(chunk);
                }
            }
        }
        // Nearest first, like a server sends them
        chunks.sort(Comparator.comparingInt(chunk -> {
            int dx = chunk.getPos().x - centerX;
            int dz = chunk.getPos().z - centerZ;
            return dx * dx + dz * dz;
        }));
        // Chunk serialization reads only block data, so it can run in parallel while the client thread waits here.
        // The light engine's section lookup caches are not thread-safe though, so light data is built on this thread.
        List<ChunkDataS2CPacket> packets = chunks.parallelStream()
                .map(chunk -> new ChunkDataS2CPacket(chunk, lighting, new BitSet(), new BitSet()))
                .toList();
        for (int i = 0; i < packets.size(); i++) {
            ((ChunkDataS2CPacketAccessor) packets.get(i)).replaymod$setLightData(
                    new LightData(chunks.get(i).getPos(), lighting, null, null));
            out.accept(packets.get(i));
        }
    }

    private void maps(Consumer<Packet<?>> out) {
        for (Map.Entry<MapIdComponent, MapState> entry : ((ClientWorldAccessor) world).replaymod$getMapStates().entrySet()) {
            MapState state = entry.getValue();
            if (state.colors.length != 128 * 128) {
                LOGGER.warn("Skipping map {} with unexpected color data size {}", entry.getKey(), state.colors.length);
                continue;
            }
            List<MapDecoration> decorations = new ArrayList<>();
            state.getDecorations().forEach(decorations::add);
            out.accept(new MapUpdateS2CPacket(entry.getKey(), state.scale, state.locked, decorations,
                    new MapState.UpdateData(0, 0, 128, 128, state.colors.clone())));
        }
    }

    /**
     * Server resource packs the client has applied. Their files are stored in the replay like for packs which are
     * sent while recording.
     */
    void resourcePacks(ResourcePackRecorder recorder, Consumer<Packet<?>> out) {
        Object manager = ((ServerResourcePackLoaderAccessor) mc.getServerResourcePackProvider()).replaymod$getManager();
        for (Object pack : List.copyOf(((ServerResourcePackManagerAccessor) manager).replaymod$getPacks())) {
            PackEntryAccessor entry = (PackEntryAccessor) pack;
            Path path = entry.replaymod$getPath();
            if (entry.replaymod$isDiscarded() || path == null) {
                continue;
            }
            ResourcePackSendS2CPacket packet = recorder.recordAppliedResourcePack(entry.replaymod$getId(), path);
            out.accept(packet);
        }
    }

    /**
     * All other entities and their state, plus the local player's own state (inventory, effects, attributes).
     * The local player entity must have been spawned before.
     */
    void entities(Consumer<Packet<?>> out, boolean playerStatus) {
        for (Entity entity : world.getEntities()) {
            if (entity.isRemoved() || entity instanceof EnderDragonPart || entity.getType().getMaxTrackDistance() <= 0) {
                continue;
            }
            if (entity != player) {
                out.accept(spawnPacket(entity));
                List<DataTracker.SerializedEntry<?>> data = entity.getDataTracker().getChangedEntries();
                if (data != null && !data.isEmpty()) {
                    out.accept(new EntityTrackerUpdateS2CPacket(entity.getId(), data));
                }
            }
            if (entity instanceof LivingEntity living) {
                Collection<EntityAttributeInstance> attributes = living.getAttributes().getAttributesToSend();
                if (!attributes.isEmpty()) {
                    out.accept(new EntityAttributesS2CPacket(entity.getId(), attributes));
                }
                if (entity != player) { // the local player's equipment is tracked by RecordingEventHandler
                    List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        ItemStack stack = living.getEquippedStack(slot);
                        if (!stack.isEmpty()) {
                            equipment.add(Pair.of(slot, stack.copy()));
                        }
                    }
                    if (!equipment.isEmpty()) {
                        out.accept(new EntityEquipmentUpdateS2CPacket(entity.getId(), equipment));
                    }
                }
                for (StatusEffectInstance effect : living.getStatusEffects()) {
                    out.accept(new EntityStatusEffectS2CPacket(entity.getId(), effect, false));
                }
            }
        }
        // Relations once all entities exist
        for (Entity entity : world.getEntities()) {
            if (entity.isRemoved()) {
                continue;
            }
            if (entity.hasPassengers()) {
                out.accept(new EntityPassengersSetS2CPacket(entity));
            }
            if (entity instanceof Leashable leashable && leashable.isLeashed() && leashable.getLeashHolder() != null) {
                out.accept(new EntityAttachS2CPacket(entity, leashable.getLeashHolder()));
            }
        }

        if (playerStatus) {
            out.accept(new ExperienceBarUpdateS2CPacket(player.experienceProgress, player.totalExperience,
                    player.experienceLevel));
            out.accept(new HealthUpdateS2CPacket(player.getHealth(), player.getHungerManager().getFoodLevel(),
                    player.getHungerManager().getSaturationLevel()));
            out.accept(new UpdateSelectedSlotS2CPacket(player.getInventory().getSelectedSlot()));
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack stack : player.playerScreenHandler.getStacks()) {
                stacks.add(stack.copy());
            }
            out.accept(new InventoryS2CPacket(player.playerScreenHandler.syncId,
                    player.playerScreenHandler.getRevision(), stacks,
                    player.playerScreenHandler.getCursorStack().copy()));
        }
    }

    private static Packet<?> spawnPacket(Entity entity) {
        try {
            // Lets entities with custom spawn data (paintings, falling blocks, ...) build their own packet
            return entity.createSpawnPacket(new EntityTrackerEntry(null, entity,
                    entity.getType().getTrackTickInterval(), entity.getType().alwaysUpdateVelocity(), NO_OP_SENDER));
        } catch (Exception e) {
            LOGGER.debug("Falling back to generic spawn packet for {}", entity, e);
            return new EntitySpawnS2CPacket(entity.getId(), entity.getUuid(), entity.getX(), entity.getY(),
                    entity.getZ(), entity.getPitch(), entity.getYaw(), entity.getType(), 0, entity.getVelocity(),
                    entity.getHeadYaw());
        }
    }
}
