package com.kevin.tiertagger.model;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.TierTagger;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public record PlayerInfo(String uuid, String name, Map<String, Ranking> rankings, String region, int points,
                         int overall, List<Badge> badges, @SerializedName("combat_master") boolean combatMaster) {
    public record Ranking(int tier, int pos, @Nullable @SerializedName("peak_tier") Integer peakTier,
                          @Nullable @SerializedName("peak_pos") Integer peakPos, long attained,
                          boolean retired) {

        /**
         * Lower is better.
         */
        public int comparableTier() {
            return tier * 2 + pos;
        }

        /**
         * Lower is better.
         */
        public int comparablePeak() {
            if (peakTier == null || peakPos == null) {
                return Integer.MAX_VALUE;
            } else {
                return peakTier * 2 + peakPos;
            }
        }

        public NamedRanking asNamed(GameMode mode) {
            return new NamedRanking(mode, this);
        }
    }

    public record NamedRanking(@Nullable GameMode mode, Ranking ranking) {
    }

    public record Badge(String title, String desc) {
    }

    private static final Map<String, Integer> REGION_COLORS = Map.of(
            "NA", 0xff6a6e,
            "EU", 0x6aff6e,
            "SA", 0xff9900,
            "AU", 0xf6b26b,
            "ME", 0xffd966,
            "AS", 0xc27ba0,
            "AF", 0x674ea7
    );

    /**
     * Get player info from Discord bot API
     * Note: Discord bot has simplified API - returns just tier and gameMode
     * This builds a PlayerInfo object from the limited data
     */
    public static CompletableFuture<PlayerInfo> get(HttpClient client, UUID uuid) {
        String apiUrl = TierTagger.getManager().getConfig().getApiUrl();

        // Validate API URL
        if (apiUrl == null || apiUrl.isEmpty() || apiUrl.equals("null")) {
            TierTagger.getLogger().debug("API URL is not configured properly!");
            return CompletableFuture.completedFuture(createEmptyPlayerInfo(uuid.toString()));
        }

        // Remove trailing slash if present
        if (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }

        String endpoint = apiUrl + "/tiers/" + uuid.toString().replace("-", "");

        TierTagger.getLogger().debug("Fetching tier from: {}", endpoint);

        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .GET()
                .timeout(java.time.Duration.ofSeconds(3))  // 3 second timeout instead of default
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(HttpResponse::body)
                .thenApply(s -> {
                    try {
                        JsonObject json = TierTagger.GSON.fromJson(s, JsonObject.class);

                        // New API format: {"sword": "H5", "crystal": "H5", "diapot": "L5", ...}
                        Map<String, Ranking> rankings = new HashMap<>();

                        for (String gameMode : json.keySet()) {
                            if (json.get(gameMode).isJsonNull()) {
                                // No tier for this mode
                                continue;
                            }

                            String tierShort = json.get(gameMode).getAsString();  // "H5", "L5", etc.

                            if (tierShort == null || tierShort.length() != 2) {
                                continue;
                            }

                            // Convert H5 -> HT5, L5 -> LT5
                            String tierFull = tierShort.charAt(0) + "T" + tierShort.charAt(1);

                            // Parse tier number and position
                            int tierNum = Character.getNumericValue(tierFull.charAt(2));
                            int pos = tierFull.startsWith("HT") ? 0 : 1;

                            // Create ranking for this game mode
                            Ranking ranking = new Ranking(
                                    tierNum,
                                    pos,
                                    null,
                                    null,
                                    System.currentTimeMillis() / 1000,
                                    false
                            );

                            rankings.put(gameMode, ranking);
                        }

                        if (rankings.isEmpty()) {
                            return createEmptyPlayerInfo(uuid.toString());
                        }

                        TierTagger.getLogger().debug("Successfully fetched {} tiers for {}", rankings.size(), uuid);

                        return new PlayerInfo(
                                uuid.toString(),
                                "",
                                rankings,
                                "N/A",
                                0,
                                0,
                                new ArrayList<>(),
                                false
                        );
                    } catch (Exception e) {
                        TierTagger.getLogger().warn("Error parsing tier data for {}: {}", uuid, e.getMessage());
                        return createEmptyPlayerInfo(uuid.toString());
                    }
                })
                .exceptionally(throwable -> {
                    TierTagger.getLogger().debug("Failed to fetch tier for {} (timeout or error): {}", uuid, throwable.getMessage());
                    return createEmptyPlayerInfo(uuid.toString());
                })
                .whenComplete((i, t) -> {
                    if (t != null) TierTagger.getLogger().debug("Error getting player info ({}): {}", uuid, t.getMessage());
                });
    }

    /**
     * Create an empty PlayerInfo object
     */
    private static PlayerInfo createEmptyPlayerInfo(String uuid) {
        return new PlayerInfo(
                uuid,
                "",
                new HashMap<>(),
                "N/A",
                0,
                0,
                new ArrayList<>(),
                false
        );
    }

    /**
     * Get rankings from Discord bot API
     * Returns map of gameMode -> Ranking
     */
    public static CompletableFuture<Map<String, Ranking>> getRankings(HttpClient client, UUID uuid) {
        // Discord bot only returns best tier, so we call the same endpoint
        return get(client, uuid)
                .thenApply(PlayerInfo::rankings)
                .exceptionally(throwable -> {
                    TierTagger.getLogger().warn("Failed to get rankings for {}: {}", uuid, throwable.getMessage());
                    return new HashMap<>();
                });
    }

    /**
     * Search player by name - Not supported by Discord bot
     * Discord bot uses UUID-based lookups only
     */
    public static CompletableFuture<PlayerInfo> search(HttpClient client, String query) {
        // Not supported by Discord bot - would need to add endpoint
        TierTagger.getLogger().warn("Player search by name not supported by Discord bot API");

        // Return empty result
        return CompletableFuture.completedFuture(new PlayerInfo(
                "",
                query,
                new HashMap<>(),
                "N/A",
                0,
                0,
                new ArrayList<>(),
                false
        ));
    }

    public int getRegionColor() {
        return REGION_COLORS.getOrDefault(this.region.toUpperCase(Locale.ROOT), 0xffffff);
    }

    public static Optional<NamedRanking> getHighestRanking(Map<String, Ranking> rankings) {
        return rankings.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .min(Comparator.comparingInt(e -> e.getValue().comparableTier()))
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())));
    }

    @Getter
    @AllArgsConstructor
    public enum PointInfo {
        COMBAT_GRANDMASTER("Combat Grandmaster", 0xE6C622, 0xFDE047),
        COMBAT_MASTER("Combat Master", 0xFBB03B, 0xFFD13A),
        COMBAT_ACE("Combat Ace", 0xCD285C, 0xD65474),
        COMBAT_SPECIALIST("Combat Specialist", 0xAD78D8, 0xC7A3E8),
        COMBAT_CADET("Combat Cadet", 0x9291D9, 0xADACE2),
        COMBAT_NOVICE("Combat Novice", 0x9291D9, 0xFFFFFF),
        ROOKIE("Rookie", 0x6C7178, 0x8B979C),
        UNRANKED("Unranked", 0xFFFFFF, 0xFFFFFF);

        private final String title;
        private final int color;
        private final int accentColor;
    }

    public PointInfo getPointInfo() {
        // Discord bot doesn't have points system, always return UNRANKED
        return PointInfo.UNRANKED;
    }

    public List<NamedRanking> getSortedTiers() {
        List<NamedRanking> tiers = new ArrayList<>(this.rankings.entrySet().stream()
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())))
                .toList());

        tiers.sort(Comparator.comparing((NamedRanking a) -> a.ranking.retired, Boolean::compare)
                .thenComparingInt(a -> a.ranking.tier)
                .thenComparingInt(a -> a.ranking.pos));

        return tiers;
    }
}