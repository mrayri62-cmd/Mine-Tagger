package com.kevin.tiertagger;

import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class TierCache {
    private static final List<GameMode> GAMEMODES = new ArrayList<>();
    private static final Map<UUID, Optional<Map<String, PlayerInfo.Ranking>>> TIERS = new ConcurrentHashMap<>();

    public static void init() {
        try {
            GAMEMODES.clear();
            // Fetch game modes from Discord bot (or use hardcoded list)
            GAMEMODES.addAll(GameMode.fetchGamemodes(TierTagger.getClient()).get());
            TierTagger.getLogger().info("Found {} tierlists: {}", GAMEMODES.size(), GAMEMODES.stream().map(GameMode::id).toList());
        } catch (ExecutionException e) {
            TierTagger.getLogger().error("Failed to load gamemodes!", e);
            // Fallback to hardcoded list if API fails
            loadDefaultGamemodes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadDefaultGamemodes();
        }
    }

    /**
     * Fallback: Load default game modes if API fetch fails
     */
    private static void loadDefaultGamemodes() {
        GAMEMODES.clear();
        GAMEMODES.add(new GameMode("sword", "Sword"));
        GAMEMODES.add(new GameMode("smp", "SMP"));
        GAMEMODES.add(new GameMode("uhc", "UHC"));
        GAMEMODES.add(new GameMode("nethpot", "NethPot"));
        GAMEMODES.add(new GameMode("diapot", "DiaPot"));
        GAMEMODES.add(new GameMode("mace", "Mace"));
        GAMEMODES.add(new GameMode("crystal", "Crystal"));
        TierTagger.getLogger().info("Loaded {} default game modes", GAMEMODES.size());
    }

    public static List<GameMode> getGamemodes() {
        if (GAMEMODES.isEmpty()) {
            return Collections.singletonList(GameMode.NONE);
        } else {
            return GAMEMODES;
        }
    }

    /**
     * Get player rankings from cache or fetch from Discord bot
     * Note: Discord bot returns only the best tier, not all tiers per mode
     */
    public static Optional<Map<String, PlayerInfo.Ranking>> getPlayerRankings(UUID uuid) {
        return TIERS.computeIfAbsent(uuid, u -> {
            // Only fetch for valid Minecraft UUIDs (version 4)
            if (uuid.version() != 4) {
                return Optional.empty();
            }

            try {
                // Fetch rankings asynchronously and update cache when complete
                PlayerInfo.getRankings(TierTagger.getClient(), uuid)
                        .thenAccept(rankings -> {
                            if (rankings != null && !rankings.isEmpty()) {
                                TIERS.put(uuid, Optional.of(rankings));
                            } else {
                                TIERS.put(uuid, Optional.empty());
                            }
                        })
                        .exceptionally(throwable -> {
                            TierTagger.getLogger().debug("Failed to fetch rankings for {}: {}", uuid, throwable.getMessage());
                            TIERS.put(uuid, Optional.empty());
                            return null;
                        });
            } catch (Exception e) {
                TierTagger.getLogger().error("Error initiating fetch for {}: {}", uuid, e.getMessage());
                return Optional.empty();
            }

            // Return empty initially, will be populated when fetch completes
            return Optional.empty();
        });
    }

    /**
     * Search player by name
     * Note: Discord bot doesn't support name search (UUID-only)
     * This will return empty results
     */
    public static CompletableFuture<PlayerInfo> searchPlayer(String query) {
        TierTagger.getLogger().warn("Player search by name is not supported by Discord bot API");

        // Discord bot doesn't support name search
        // Return a CompletableFuture with empty PlayerInfo
        return CompletableFuture.completedFuture(new PlayerInfo(
                "",  // Empty UUID
                query,  // Use query as name
                new HashMap<>(),  // No rankings
                "N/A",  // No region
                0,  // No points
                0,  // No overall
                new ArrayList<>(),  // No badges
                false  // Not combat master
        ));
    }

    public static void clearCache() {
        TIERS.clear();
        TierTagger.getLogger().info("Tier cache cleared");
    }

    public static GameMode findNextMode(GameMode current) {
        if (GAMEMODES.isEmpty()) {
            return GameMode.NONE;
        } else {
            return GAMEMODES.get((GAMEMODES.indexOf(current) + 1) % GAMEMODES.size());
        }
    }

    public static Optional<GameMode> findMode(String id) {
        return GAMEMODES.stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst();
    }

    public static GameMode findModeOrUgly(String id) {
        return findMode(id).orElseGet(() -> new GameMode(id, id));
    }

    /**
     * Parse UUID string with or without dashes
     */
    private static UUID parseUUID(String uuid) {
        try {
            // Try standard UUID format first (with dashes)
            return UUID.fromString(uuid);
        } catch (Exception e) {
            // Handle UUID without dashes (32 hex characters)
            if (uuid.length() == 32) {
                try {
                    long mostSignificant = Long.parseUnsignedLong(uuid.substring(0, 16), 16);
                    long leastSignificant = Long.parseUnsignedLong(uuid.substring(16), 16);
                    return new UUID(mostSignificant, leastSignificant);
                } catch (Exception ex) {
                    TierTagger.getLogger().error("Failed to parse UUID: {}", uuid, ex);
                    return UUID.randomUUID(); // Fallback
                }
            } else {
                TierTagger.getLogger().error("Invalid UUID length: {}", uuid);
                return UUID.randomUUID(); // Fallback
            }
        }
    }

    private TierCache() {
    }
}