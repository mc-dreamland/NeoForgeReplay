package com.replaymod.replay;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PackStackTest {
    private static final PackDescriptor A = pack("00000000-0000-0000-0000-000000000001", 'a');
    private static final PackDescriptor A2 = pack("00000000-0000-0000-0000-000000000001", 'c');
    private static final PackDescriptor B = pack("00000000-0000-0000-0000-000000000002", 'b');

    @Test
    public void duplicatePushKeepsStackIdentity() {
        PackStack stack = PackStack.empty().push(A);
        assertSame(stack, stack.push(A));
    }

    @Test
    public void sameUuidWithNewContentReplacesPack() {
        assertEquals(PackStack.of(A2), PackStack.of(A).push(A2));
    }

    @Test
    public void repeatedPushMovesPackToHighestPriority() {
        assertEquals(PackStack.of(B, A), PackStack.of(A, B).push(A));
    }

    @Test
    public void popOneAndPopAllPreserveOrder() {
        assertEquals(PackStack.of(B), PackStack.of(A, B).pop(A.id()));
        assertEquals(PackStack.empty(), PackStack.of(A, B).pop(A.id()).pop(B.id()));
    }

    @Test
    public void transitionRemovesOnlySuffix() {
        PackTransition transition = PackStack.of(A, B).transitionTo(PackStack.of(A));
        assertEquals(1, transition.commonPrefixLength());
        assertEquals(java.util.Collections.singletonList(B), transition.removals());
        assertEquals(java.util.Collections.emptyList(), transition.additions());
    }

    @Test
    public void transitionAddsOnlySuffix() {
        PackTransition transition = PackStack.of(A).transitionTo(PackStack.of(A, B));
        assertEquals(1, transition.commonPrefixLength());
        assertEquals(java.util.Collections.emptyList(), transition.removals());
        assertEquals(java.util.Collections.singletonList(B), transition.additions());
    }

    @Test
    public void reorderedTransitionReplacesWholeStack() {
        PackTransition transition = PackStack.of(A, B).transitionTo(PackStack.of(B, A));
        assertEquals(0, transition.commonPrefixLength());
        assertEquals(java.util.Arrays.asList(B, A), transition.removals());
        assertEquals(java.util.Arrays.asList(B, A), transition.additions());
    }

    private static PackDescriptor pack(String id, char hashCharacter) {
        String hash = String.valueOf(hashCharacter).repeat(40);
        return new PackDescriptor(UUID.fromString(id), hash, Paths.get(hash + ".zip"));
    }
}
