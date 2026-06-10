package com.backrooms.mod.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player noclip chance multipliers.
 * Base chance denominator: 1 in BASE_DENOM per tick.
 * Multiplier stacks each time a player is noclipped (higher multiplier = more frequent).
 */
public class NoclipChanceManager {

    /** Base denominator: 1 in 72000 chance per tick (~1 hour average at 20 TPS) */
    private static final int BASE_DENOM = 72000;

    /** Minimum denominator (caps max frequency) */
    private static final int MIN_DENOM = 1200;

    private static final Map<UUID, Integer> multipliers = new HashMap<>();

    /**
     * Returns the effective chance denominator for a player.
     * Higher multiplier = lower denominator = higher chance.
     */
    public static int getEffectiveChanceDenominator(UUID uuid) {
        int multiplier = multipliers.getOrDefault(uuid, 1);
        int denom = BASE_DENOM / multiplier;
        return Math.max(denom, MIN_DENOM);
    }

    /**
     * Returns the current multiplier for a player (default 1).
     */
    public static int getMultiplier(UUID uuid) {
        return multipliers.getOrDefault(uuid, 1);
    }

    /**
     * Sets the multiplier for a player to a specific value.
     */
    public static void setMultiplier(UUID uuid, int value) {
        multipliers.put(uuid, Math.max(1, value));
    }

    /**
     * Increments the multiplier for a player after a noclip event.
     */
    public static void incrementMultiplier(UUID uuid) {
        int current = multipliers.getOrDefault(uuid, 1);
        multipliers.put(uuid, current + 1);
    }

    /**
     * Resets the multiplier for a player back to 1.
     */
    public static void resetMultiplier(UUID uuid) {
        multipliers.remove(uuid);
    }

    /**
     * Removes all data for a player (e.g. on logout).
     */
    public static void clearPlayer(UUID uuid) {
        multipliers.remove(uuid);
    }
}
