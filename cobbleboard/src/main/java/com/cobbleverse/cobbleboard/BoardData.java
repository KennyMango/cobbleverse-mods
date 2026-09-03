package com.cobbleverse.cobbleboard;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BoardData {
    public Map<String, BoardDefinition> boards = new LinkedHashMap<>();

    public static final class BoardDefinition {
        public String objective;
        public String title;
        public String dimension = "minecraft:overworld";
        public double x;
        public double y;
        public double z;
        public int limit = 10;

        // v0.3 style defaults
        public double lineSpacing = 0.28D;
        public double titleSpacing = 0.378D; // old 0.28 * 1.35 appearance
        public String titleColor = "yellow";
        public String nameColor = "aqua";
        public String scoreColor = "red";

        // v0.4 display mode. "panel" uses one multiline Text Display entity.
        // "stacked" preserves the legacy one-armor-stand-per-line renderer.
        public String displayMode = "panel";
        public double boardScale = 1.35D;
        public int boardWidth = 240;

        public BoardDefinition() {}

        public BoardDefinition(String objective, String title, String dimension,
                               double x, double y, double z, int limit) {
            this.objective = objective;
            this.title = title;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.limit = limit;
        }

        public void resetStyle() {
            lineSpacing = 0.28D;
            titleSpacing = 0.378D;
            titleColor = "yellow";
            nameColor = "aqua";
            scoreColor = "red";
            displayMode = "panel";
            boardScale = 1.35D;
            boardWidth = 240;
        }
    }
}
