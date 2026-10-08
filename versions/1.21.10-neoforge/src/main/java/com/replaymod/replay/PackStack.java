package com.replaymod.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class PackStack {
    private static final PackStack EMPTY = new PackStack(Collections.emptyList());

    private final List<PackDescriptor> packs;

    private PackStack(List<PackDescriptor> packs) {
        this.packs = Collections.unmodifiableList(packs);
    }

    static PackStack empty() {
        return EMPTY;
    }

    static PackStack of(PackDescriptor... packs) {
        PackStack stack = empty();
        for (PackDescriptor pack : packs) {
            stack = stack.push(pack);
        }
        return stack;
    }

    PackStack push(PackDescriptor pack) {
        Objects.requireNonNull(pack, "pack");
        List<PackDescriptor> updated = new ArrayList<>(packs.size() + 1);
        for (PackDescriptor existing : packs) {
            if (!existing.id().equals(pack.id())) {
                updated.add(existing);
            }
        }
        updated.add(pack);
        PackStack result = new PackStack(updated);
        return equals(result) ? this : result;
    }

    PackStack pop(UUID id) {
        Objects.requireNonNull(id, "id");
        List<PackDescriptor> updated = new ArrayList<>(packs.size());
        for (PackDescriptor existing : packs) {
            if (!existing.id().equals(id)) {
                updated.add(existing);
            }
        }
        return updated.size() == packs.size() ? this : new PackStack(updated);
    }

    List<PackDescriptor> entries() {
        return packs;
    }

    PackTransition transitionTo(PackStack target) {
        int commonPrefix = 0;
        int limit = Math.min(packs.size(), target.packs.size());
        while (commonPrefix < limit && packs.get(commonPrefix).equals(target.packs.get(commonPrefix))) {
            commonPrefix++;
        }

        List<PackDescriptor> removals = new ArrayList<>();
        for (int i = packs.size() - 1; i >= commonPrefix; i--) {
            removals.add(packs.get(i));
        }
        List<PackDescriptor> additions = new ArrayList<>(target.packs.subList(commonPrefix, target.packs.size()));
        return new PackTransition(this, target, commonPrefix, removals, additions);
    }

    String signature() {
        if (packs.isEmpty()) return "empty";
        StringBuilder result = new StringBuilder();
        for (PackDescriptor pack : packs) {
            if (result.length() > 0) result.append(',');
            result.append(pack.id().toString(), 0, 8)
                    .append(':')
                    .append(pack.contentSha1(), 0, Math.min(8, pack.contentSha1().length()));
        }
        return result.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PackStack && packs.equals(((PackStack) other).packs);
    }

    @Override
    public int hashCode() {
        return packs.hashCode();
    }
}
