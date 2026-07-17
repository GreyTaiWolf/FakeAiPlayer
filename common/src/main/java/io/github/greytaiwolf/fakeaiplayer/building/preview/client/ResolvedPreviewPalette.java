package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves a committed preview palette once per immutable session revision. */
public final class ResolvedPreviewPalette {
    private UUID sessionId;
    private int transformRevision = -1;
    private String previewHash = "";
    private List<Entry> entries = List.of();

    public List<Entry> resolve(BuildingPreviewClientState.Snapshot preview) {
        if (isCurrent(preview)) {
            return entries;
        }

        List<Entry> next = new ArrayList<>(preview.palette().size());
        for (BlockStateSpec spec : preview.palette()) {
            try {
                next.add(new Entry(spec, BlockStateResolver.resolve(spec)));
            } catch (RuntimeException exception) {
                // An unknown registry entry or property must not crash the client renderer. The
                // classifier keeps the cell visible and the model pass falls back to a wireframe.
                next.add(new Entry(spec, null));
            }
        }
        entries = List.copyOf(next);
        sessionId = preview.sessionId();
        transformRevision = preview.transformRevision();
        previewHash = preview.previewHash();
        return entries;
    }

    public void clear() {
        sessionId = null;
        transformRevision = -1;
        previewHash = "";
        entries = List.of();
    }

    private boolean isCurrent(BuildingPreviewClientState.Snapshot preview) {
        return preview.sessionId().equals(sessionId)
                && preview.transformRevision() == transformRevision
                && preview.previewHash().equals(previewHash);
    }

    public record Entry(BlockStateSpec spec, BlockState state) {
        public Entry {
            if (spec == null) {
                throw new IllegalArgumentException("preview palette spec is missing");
            }
        }

        public boolean resolved() {
            return state != null;
        }
    }
}
