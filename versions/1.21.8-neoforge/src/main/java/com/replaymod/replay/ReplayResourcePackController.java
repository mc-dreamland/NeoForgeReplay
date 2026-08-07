package com.replaymod.replay;

import com.replaymod.replaystudio.replay.ReplayFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static com.replaymod.replay.ReplayModReplay.LOGGER;

final class ReplayResourcePackController implements AutoCloseable {
    enum State {
        CONFIRMED,
        REBUILDING,
        RECONCILING,
        UNKNOWN
    }

    interface Completion {
        void success();

        void failure(String reason);
    }

    interface RuntimeAdapter {
        void apply(long generation, PackTransition transition, Completion completion);

        void cancelPending();

        void warnUser(String reason);
    }

    private final boolean enabled;
    private final ReplayResourcePackResolver resolver;
    private final RuntimeAdapter runtime;

    private State state;
    private PackStack confirmed = PackStack.empty();
    private PackStack requested = PackStack.empty();
    private PackStack shadow;
    private PackStack pendingTarget;
    private long pendingGeneration;
    private long generation;
    private long pendingStartedNanos;
    private boolean closed;

    static ReplayResourcePackController create(ReplayFile replayFile, boolean enabled) {
        MinecraftReplayResourcePackRuntime runtime = new MinecraftReplayResourcePackRuntime();
        if (!enabled) {
            LOGGER.info("Replay resource pack decision=fallback reason=kill-switch");
            return new ReplayResourcePackController(false, null, runtime, State.CONFIRMED);
        }
        try {
            return new ReplayResourcePackController(true, new ReplayResourcePackResolver(replayFile), runtime,
                    State.CONFIRMED);
        } catch (IOException exception) {
            LOGGER.error("Replay resource pack decision=fallback reason=extractor-init errorType={}",
                    exception.getClass().getSimpleName());
            runtime.warnUser("extractor-init");
            return new ReplayResourcePackController(true, null, runtime, State.UNKNOWN);
        }
    }

    ReplayResourcePackController(boolean enabled, ReplayResourcePackResolver resolver, RuntimeAdapter runtime) {
        this(enabled, resolver, runtime, State.CONFIRMED);
    }

    private ReplayResourcePackController(boolean enabled, ReplayResourcePackResolver resolver,
                                         RuntimeAdapter runtime, State initialState) {
        this.enabled = enabled;
        this.resolver = resolver;
        this.runtime = runtime;
        this.state = initialState;
    }

    synchronized boolean handlePush(UUID id, String url) {
        if (!canOptimize()) return false;
        if (url == null || !url.startsWith("replay://")) {
            markUnknown("external-url", null);
            return false;
        }

        final int requestId;
        try {
            String value = url.substring("replay://".length());
            if (value.isEmpty() || !value.matches("[0-9]+")) throw new NumberFormatException();
            requestId = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            markUnknown("malformed-replay-url", exception);
            return true;
        }

        try {
            applyTarget(currentTarget().push(resolver.resolve(id, requestId)));
        } catch (IOException | RuntimeException exception) {
            markUnknown(safeReason(exception, "pack-resolution"), exception);
        }
        return true;
    }

    synchronized boolean handlePop(Optional<UUID> id) {
        if (!canOptimize()) return false;
        applyTarget(id.isPresent() ? currentTarget().pop(id.get()) : PackStack.empty());
        return true;
    }

    synchronized boolean beginRebuild() {
        if (!canOptimize()) {
            LOGGER.info("Replay resource pack generation={} decision=fallback state={}", generation, state);
            return false;
        }
        shadow = PackStack.empty();
        state = State.REBUILDING;
        LOGGER.debug("Replay resource pack generation={} decision=rebuild oldStack={}",
                generation, confirmed.signature());
        return true;
    }

    synchronized void finishRebuild() {
        if (closed || shadow == null || state == State.UNKNOWN) return;
        requested = shadow;
        shadow = null;
        if (pendingTarget != null) {
            state = State.RECONCILING;
            LOGGER.debug("Replay resource pack generation={} decision=queue targetStack={}",
                    pendingGeneration, requested.signature());
        } else {
            reconcileLatest();
        }
    }

    synchronized boolean preservesPacksDuringRestart() {
        return enabled && !closed && state != State.UNKNOWN && shadow != null;
    }

    synchronized State state() {
        return state;
    }

    synchronized PackStack confirmedStack() {
        return confirmed;
    }

    private boolean canOptimize() {
        return enabled && !closed && state != State.UNKNOWN && resolver != null;
    }

    private PackStack currentTarget() {
        return shadow != null ? shadow : requested;
    }

    private void applyTarget(PackStack target) {
        if (shadow != null) {
            shadow = target;
            return;
        }
        requested = target;
        if (pendingTarget == null) {
            reconcileLatest();
        } else if (pendingTarget.equals(target)) {
            LOGGER.debug("Replay resource pack generation={} decision=merge targetStack={}",
                    pendingGeneration, target.signature());
        } else {
            LOGGER.debug("Replay resource pack generation={} decision=queue targetStack={}",
                    pendingGeneration, target.signature());
        }
    }

    private void reconcileLatest() {
        if (confirmed.equals(requested)) {
            state = State.CONFIRMED;
            LOGGER.debug("Replay resource pack generation={} decision=reuse stack={}",
                    generation, confirmed.signature());
            return;
        }

        PackTransition transition = confirmed.transitionTo(requested);
        if (transition.isEmpty()) {
            state = State.CONFIRMED;
            return;
        }

        long operationGeneration = ++generation;
        pendingGeneration = operationGeneration;
        pendingTarget = requested;
        pendingStartedNanos = System.nanoTime();
        state = State.RECONCILING;
        LOGGER.info("Replay resource pack generation={} decision=reconcile oldStack={} newStack={} remove={} add={}",
                operationGeneration, transition.from().signature(), transition.to().signature(),
                transition.removals().size(), transition.additions().size());

        runtime.apply(operationGeneration, transition, new Completion() {
            @Override
            public void success() {
                completeSuccess(operationGeneration);
            }

            @Override
            public void failure(String reason) {
                completeFailure(operationGeneration, reason);
            }
        });
    }

    private synchronized void completeSuccess(long operationGeneration) {
        if (closed || pendingTarget == null || pendingGeneration != operationGeneration) {
            LOGGER.debug("Replay resource pack generation={} decision=ignore-stale-success activeGeneration={}",
                    operationGeneration, pendingGeneration);
            return;
        }
        long elapsedMillis = (System.nanoTime() - pendingStartedNanos) / 1_000_000L;
        confirmed = pendingTarget;
        pendingTarget = null;
        LOGGER.info("Replay resource pack generation={} decision=confirmed stack={} reloadMillis={}",
                operationGeneration, confirmed.signature(), elapsedMillis);

        if (shadow != null) {
            state = State.REBUILDING;
        } else {
            reconcileLatest();
        }
    }

    private synchronized void completeFailure(long operationGeneration, String reason) {
        if (closed || pendingTarget == null || pendingGeneration != operationGeneration) {
            LOGGER.debug("Replay resource pack generation={} decision=ignore-stale-failure activeGeneration={}",
                    operationGeneration, pendingGeneration);
            return;
        }
        markUnknown(reason == null ? "reload-failure" : reason, null);
    }

    private void markUnknown(String reason, Throwable throwable) {
        generation++;
        pendingTarget = null;
        shadow = null;
        requested = confirmed;
        state = State.UNKNOWN;
        runtime.cancelPending();
        LOGGER.error("Replay resource pack generation={} decision=fallback reason={} errorType={}",
                generation, reason, throwable == null ? "none" : throwable.getClass().getSimpleName());
        runtime.warnUser(reason);
    }

    private static String safeReason(Exception exception, String fallback) {
        String message = exception.getMessage();
        if (message != null && message.matches("[a-z0-9-]+")) return message;
        return fallback;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        pendingTarget = null;
        shadow = null;
        runtime.cancelPending();
        if (resolver != null) {
            try {
                resolver.close();
            } catch (IOException exception) {
                LOGGER.warn("Replay resource pack cleanup failed errorType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }
}
