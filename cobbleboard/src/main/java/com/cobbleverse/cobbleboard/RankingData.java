package com.cobbleverse.cobbleboard;

import java.util.HashMap;
import java.util.Map;

public final class RankingData {
    public long nextSequence = 1L;
    public Map<String, Map<String, PlayerStanding>> objectives = new HashMap<>();

    public static final class PlayerStanding {
        public int score;
        public long achievedOrder;
        public Integer manualRank;

        public PlayerStanding() {}

        public PlayerStanding(int score, long achievedOrder) {
            this.score = score;
            this.achievedOrder = achievedOrder;
        }
    }
}
