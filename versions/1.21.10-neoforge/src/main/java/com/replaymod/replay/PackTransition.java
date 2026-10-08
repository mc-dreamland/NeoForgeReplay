package com.replaymod.replay;

import java.util.Collections;
import java.util.List;

final class PackTransition {
    private final PackStack from;
    private final PackStack to;
    private final int commonPrefixLength;
    private final List<PackDescriptor> removals;
    private final List<PackDescriptor> additions;

    PackTransition(PackStack from, PackStack to, int commonPrefixLength,
                   List<PackDescriptor> removals, List<PackDescriptor> additions) {
        this.from = from;
        this.to = to;
        this.commonPrefixLength = commonPrefixLength;
        this.removals = Collections.unmodifiableList(removals);
        this.additions = Collections.unmodifiableList(additions);
    }

    PackStack from() {
        return from;
    }

    PackStack to() {
        return to;
    }

    int commonPrefixLength() {
        return commonPrefixLength;
    }

    List<PackDescriptor> removals() {
        return removals;
    }

    List<PackDescriptor> additions() {
        return additions;
    }

    boolean isEmpty() {
        return removals.isEmpty() && additions.isEmpty();
    }
}
