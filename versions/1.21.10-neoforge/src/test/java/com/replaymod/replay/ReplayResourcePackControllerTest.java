package com.replaymod.replay;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReplayResourcePackControllerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final byte[] CONTENT = "replay-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String CONTENT_SHA1 = "04c75a68e315429c9d1ddd9e8ae4c882c09db4dd";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mergesDuplicatePendingTarget() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);

        assertTrue(controller.handlePush(A, "replay://7"));
        assertTrue(controller.handlePush(A, "replay://7"));
        assertEquals(1, runtime.batches.size());
        runtime.succeed(0);
        assertEquals(ReplayResourcePackController.State.CONFIRMED, controller.state());
        controller.close();
    }

    @Test
    public void queuedChangesCollapseIntoLatestTarget() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);

        controller.handlePush(A, "replay://7");
        controller.handlePush(B, "replay://7");
        controller.handlePush(C, "replay://7");
        assertEquals(1, runtime.batches.size());

        runtime.succeed(0);
        assertEquals(2, runtime.batches.size());
        assertEquals(2, runtime.batches.get(1).transition.additions().size());
        runtime.succeed(1);
        assertEquals(ReplayResourcePackController.State.CONFIRMED, controller.state());
        controller.close();
    }

    @Test
    public void rebuildProducesOneFinalTransition() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);
        controller.handlePush(A, "replay://7");
        runtime.succeed(0);

        assertTrue(controller.beginRebuild());
        controller.handlePush(A, "replay://7");
        controller.handlePush(B, "replay://7");
        controller.handlePop(Optional.of(B));
        controller.handlePush(C, "replay://7");
        assertEquals(1, runtime.batches.size());

        controller.finishRebuild();
        assertEquals(2, runtime.batches.size());
        assertEquals(1, runtime.batches.get(1).transition.additions().size());
        runtime.succeed(1);
        controller.close();
    }

    @Test
    public void identicalRebuildNeedsNoReload() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);
        controller.handlePush(A, "replay://7");
        runtime.succeed(0);

        controller.beginRebuild();
        controller.handlePush(A, "replay://7");
        controller.finishRebuild();
        assertEquals(1, runtime.batches.size());
        assertEquals(ReplayResourcePackController.State.CONFIRMED, controller.state());
        controller.close();
    }

    @Test
    public void beginRebuildOnEmptyConfirmedDoesNotSuppressLoad() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);

        // Mirrors the initial replay entry: the sender may begin a "rebuild" before any pack has
        // ever been confirmed. With nothing to preserve, pushes must load immediately instead of
        // being collected into a shadow stack that never reconciles.
        assertFalse(controller.beginRebuild());
        assertTrue(controller.handlePush(A, "replay://7"));
        assertEquals(1, runtime.batches.size());

        runtime.succeed(0);
        assertEquals(ReplayResourcePackController.State.CONFIRMED, controller.state());
        assertEquals(1, controller.confirmedStack().entries().size());
        controller.close();
    }

    @Test
    public void removeOnlyBatchWaitsForCompletion() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);
        controller.handlePush(A, "replay://7");
        runtime.succeed(0);

        controller.handlePop(Optional.of(A));
        assertEquals(ReplayResourcePackController.State.RECONCILING, controller.state());
        assertEquals(1, runtime.batches.get(1).transition.removals().size());
        assertEquals(0, runtime.batches.get(1).transition.additions().size());
        runtime.succeed(1);
        assertEquals(ReplayResourcePackController.State.CONFIRMED, controller.state());
        controller.close();
    }

    @Test
    public void failureEntersPermanentLegacyFallback() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);
        controller.handlePush(A, "replay://7");
        runtime.fail(0);

        assertEquals(ReplayResourcePackController.State.UNKNOWN, controller.state());
        assertFalse(controller.beginRebuild());
        assertFalse(controller.handlePush(B, "replay://7"));
        assertEquals(Collections.singletonList("test-failure"), runtime.warnings);
        controller.close();
    }

    @Test
    public void callbackAfterCloseIsIgnored() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ReplayResourcePackController controller = controller(runtime);
        controller.handlePush(A, "replay://7");
        controller.close();
        runtime.succeed(0);
        assertEquals(0, controller.confirmedStack().entries().size());
    }

    private ReplayResourcePackController controller(FakeRuntime runtime) throws Exception {
        ReplayResourcePackResolver.ContentSource source = new ReplayResourcePackResolver.ContentSource() {
            @Override
            public Map<Integer, String> index() {
                return Collections.singletonMap(7, CONTENT_SHA1);
            }

            @Override
            public Optional<InputStream> open(String contentSha1) {
                return Optional.of(new ByteArrayInputStream(CONTENT));
            }
        };
        ReplayResourcePackResolver resolver = new ReplayResourcePackResolver(
                source, temporaryFolder.newFolder().toPath());
        return new ReplayResourcePackController(true, resolver, runtime);
    }

    private static final class FakeRuntime implements ReplayResourcePackController.RuntimeAdapter {
        private final List<Batch> batches = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void apply(long generation, PackTransition transition,
                          ReplayResourcePackController.Completion completion) {
            batches.add(new Batch(generation, transition, completion));
        }

        @Override
        public void cancelPending() {
        }

        @Override
        public void warnUser(String reason) {
            warnings.add(reason);
        }

        void succeed(int index) {
            batches.get(index).completion.success();
        }

        void fail(int index) {
            batches.get(index).completion.failure("test-failure");
        }
    }

    private static final class Batch {
        private final long generation;
        private final PackTransition transition;
        private final ReplayResourcePackController.Completion completion;

        private Batch(long generation, PackTransition transition,
                      ReplayResourcePackController.Completion completion) {
            this.generation = generation;
            this.transition = transition;
            this.completion = completion;
        }
    }
}
