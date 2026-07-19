package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

/** Mutable per-frame counters that bound large preview world reads and tessellation work. */
public final class PreviewRenderBudget {
    public static final int DEFAULT_SCAN_LIMIT = 16_384;
    public static final int DEFAULT_RENDER_LIMIT = 4_096;
    /** Baked block models are substantially more expensive than wireframes. */
    public static final int DEFAULT_MODEL_LIMIT = 256;

    private final int scanLimit;
    private final int renderLimit;
    private final int modelLimit;
    private int scanned;
    private int rendered;
    private int models;

    public PreviewRenderBudget() {
        this(DEFAULT_SCAN_LIMIT, DEFAULT_RENDER_LIMIT, DEFAULT_MODEL_LIMIT);
    }

    public PreviewRenderBudget(int scanLimit, int renderLimit, int modelLimit) {
        if (scanLimit < 0 || renderLimit < 0 || modelLimit < 0 || modelLimit > renderLimit) {
            throw new IllegalArgumentException("invalid preview render budget");
        }
        this.scanLimit = scanLimit;
        this.renderLimit = renderLimit;
        this.modelLimit = modelLimit;
    }

    public boolean tryScan() {
        if (scanned >= scanLimit) {
            return false;
        }
        scanned++;
        return true;
    }

    public boolean tryRender(Cost cost) {
        if (cost == null || rendered >= renderLimit || cost == Cost.MODEL && models >= modelLimit) {
            return false;
        }
        rendered++;
        if (cost == Cost.MODEL) {
            models++;
        }
        return true;
    }

    public int scanned() {
        return scanned;
    }

    public int rendered() {
        return rendered;
    }

    public int models() {
        return models;
    }

    public enum Cost {
        MODEL,
        LINE
    }
}
