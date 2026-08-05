package com.replaymod.render.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.replaymod.core.ReplayMod.MOD_ID;

/**
 * Uses the "Flawless Frames" FREX feature which allows us to instruct third-party mods to sacrifice performance (even
 * beyond the point where it can no longer achieve interactive frame rates) in exchange for a noticeable boost to
 * quality.
 * In particular also to force-load all chunks with Canvas/Sodium/Bobby.
 *
 * See https://github.com/grondag/frex/pull/9
 */
public class FlawlessFrames {
    private static final Logger LOGGER = LogManager.getLogger("ReplayMod/FlawlessFrames");
    private static final List<Consumer<Boolean>> CONSUMERS = new CopyOnWriteArrayList<>();
    private static boolean hasSodium;

    private FlawlessFrames() {}

    public static void registerConsumer(Function<String, Consumer<Boolean>> provider) {
        Consumer<Boolean> consumer = provider.apply(MOD_ID);
        CONSUMERS.add(consumer);

        boolean sodium = provider.getClass().getName().contains(".sodium.")
                || consumer.getClass().getName().contains(".sodium.");
        if (sodium) {
            hasSodium = true;
        }
        LOGGER.info("Registered Flawless Frames controller provider={} sodium={}",
                provider.getClass().getName(), sodium);
    }

    public static void acceptController(Function<String, Consumer<Boolean>> provider) {
        registerConsumer(provider);
    }

    public static void setEnabled(boolean enabled) {
        CONSUMERS.forEach(it -> it.accept(enabled));
    }

    public static boolean hasSodium() {
        return hasSodium;
    }
}
