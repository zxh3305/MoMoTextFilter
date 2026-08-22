package com.momocraft.textfilter;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrossMessageTracker {

    private final MoMoTextFilterMod mod;
    private final ConcurrentHashMap<UUID, List<TrackingState>> trackingStates;

    public CrossMessageTracker(MoMoTextFilterMod mod) {
        this.mod = mod;
        this.trackingStates = new ConcurrentHashMap<>();
    }

    public TrackingResult checkAndTrack(ServerPlayer player, String text, String context) {
        if (text == null || text.isEmpty()) {
            cleanupExpired(player.getUUID());
            return null;
        }

        UUID playerId = player.getUUID();
        String strippedText = CharacterMapper.normalize(ColorCodeUtils.stripAllFormatting(text).toLowerCase());

        List<TrackingResult> results = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : mod.getConfigManager().getBannedWordsByLevel().entrySet()) {
            String level = entry.getKey();
            for (String bannedWord : entry.getValue()) {
                if (bannedWord == null || bannedWord.isEmpty()) continue;

                String matchedWord = matchBannedWord(strippedText, bannedWord, level);
                if (matchedWord != null) {
                    results.add(new TrackingResult(true, false, matchedWord, level, context));
                }
            }
        }

        List<TrackingState> states = trackingStates.get(playerId);
        List<TrackingState> toRemove = new ArrayList<>();

        if (states != null && !states.isEmpty()) {
            for (TrackingState state : states) {
                int levelChatboxGap = mod.getConfigManager().getChatboxGapForLevel(state.level);

                if (state.hasMatched) {
                    state.matchCounter++;
                    if (levelChatboxGap <= 0 || state.matchCounter > levelChatboxGap) {
                        toRemove.add(state);
                    } else {
                        String lowerBanned = CharacterMapper.normalize(state.bannedWord.toLowerCase());
                        boolean suffixMatch = false;
                        for (int i = 1; i <= strippedText.length() && i <= lowerBanned.length(); i++) {
                            String suffix = strippedText.substring(strippedText.length() - i);
                            if (lowerBanned.endsWith(suffix) && suffix.length() >= 1) {
                                suffixMatch = true;
                                break;
                            }
                        }
                        String matchedWord = matchBannedWord(strippedText, state.bannedWord, state.level);
                        if (suffixMatch || matchedWord != null) {
                            results.add(new TrackingResult(true, true, matchedWord != null ? matchedWord : state.bannedWord, state.level, context));
                        }
                        state.lastUpdateTime = System.currentTimeMillis();
                    }
                } else {
                    String lowerBanned = CharacterMapper.normalize(state.bannedWord.toLowerCase());
                    String remaining = lowerBanned.substring(state.currentPosition);
                    int matchLen = findPrefixMatch(strippedText, remaining);

                    if (matchLen > 0) {
                        int newPosition = state.currentPosition + matchLen;
                        state.currentPosition = newPosition;
                        state.gapCounter = 0;

                        if (newPosition >= lowerBanned.length()) {
                            results.add(new TrackingResult(true, true, state.bannedWord, state.level, context));
                            state.hasMatched = true;
                            state.matchCounter = 0;
                        }
                        state.lastUpdateTime = System.currentTimeMillis();
                    } else {
                        state.gapCounter++;
                        if (levelChatboxGap <= 0 || state.gapCounter > levelChatboxGap) {
                            toRemove.add(state);
                        } else {
                            state.lastUpdateTime = System.currentTimeMillis();
                        }
                    }
                }
            }

            states.removeAll(toRemove);
            if (states.isEmpty()) {
                trackingStates.remove(playerId);
            }
        }

        if (results.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : mod.getConfigManager().getBannedWordsByLevel().entrySet()) {
                String level = entry.getKey();
                int levelChatboxGap = mod.getConfigManager().getChatboxGapForLevel(level);

                if (levelChatboxGap <= 0) {
                    continue;
                }

                for (String bannedWord : entry.getValue()) {
                    if (bannedWord == null || bannedWord.isEmpty()) continue;

                    String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());

                    for (int i = 0; i < strippedText.length(); i++) {
                        char c = strippedText.charAt(i);
                        if (c == lowerBanned.charAt(0)) {
                            int matchLen = findPrefixMatch(strippedText.substring(i), lowerBanned);
                            if (matchLen > 0 && matchLen < lowerBanned.length()) {
                                addTrackingState(playerId, bannedWord, level, matchLen);
                                break;
                            }
                        }
                    }
                }
            }
        }

        cleanupExpired(playerId);

        if (!results.isEmpty()) {
            return results.get(0);
        }

        return null;
    }

    private int findPrefixMatch(String text, String prefix) {
        int matchLen = 0;
        for (int i = 0; i < text.length() && i < prefix.length(); i++) {
            if (text.charAt(i) == prefix.charAt(i)) {
                matchLen++;
            } else {
                break;
            }
        }
        return matchLen;
    }

    private void addTrackingState(UUID playerId, String bannedWord, String level, int currentPosition) {
        trackingStates.computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(new TrackingState(bannedWord, level, currentPosition));
    }

    private String matchBannedWord(String text, String bannedWord, String level) {
        FabricConfigManager config = mod.getConfigManager();
        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits limits = config.getMaxCharGapForLevel(level);
        boolean reverseMatch = config.isReverseMatchEnable() && config.isReverseMatchEnableForLevel(level);

        if (ColorCodeUtils.containsBannedWord(text, bannedWord, fuzzyMatch, limits, config.getWhitelist())) {
            return bannedWord;
        }

        if (reverseMatch && bannedWord.length() >= 2) {
            String reversedWord = ColorCodeUtils.reverseWord(bannedWord);
            if (ColorCodeUtils.containsBannedWord(text, reversedWord, fuzzyMatch, limits, config.getWhitelist())) {
                return reversedWord;
            }
        }

        return null;
    }

    private void cleanupExpired(UUID playerId) {
        List<TrackingState> states = trackingStates.get(playerId);
        if (states != null) {
            long now = System.currentTimeMillis();
            states.removeIf(state -> now - state.lastUpdateTime > 60000);
            if (states.isEmpty()) {
                trackingStates.remove(playerId);
            }
        }
    }

    public void cleanupAll() {
        long now = System.currentTimeMillis();
        trackingStates.forEach((uuid, states) -> {
            states.removeIf(state -> now - state.lastUpdateTime > 60000);
            if (states.isEmpty()) {
                trackingStates.remove(uuid);
            }
        });
    }

    public void removePlayer(UUID playerId) {
        trackingStates.remove(playerId);
    }

    public static class TrackingState {
        final String bannedWord;
        final String level;
        int currentPosition;
        int gapCounter;
        int matchCounter;
        boolean hasMatched;
        long lastUpdateTime;

        TrackingState(String bannedWord, String level, int currentPosition) {
            this.bannedWord = bannedWord;
            this.level = level;
            this.currentPosition = currentPosition;
            this.gapCounter = 0;
            this.matchCounter = 0;
            this.hasMatched = false;
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }

    public static class TrackingResult {
        private final boolean matched;
        private final boolean crossMessageMatch;
        private final String bannedWord;
        private final String level;
        private final String context;

        public TrackingResult(boolean matched, boolean crossMessageMatch, String bannedWord, String level, String context) {
            this.matched = matched;
            this.crossMessageMatch = crossMessageMatch;
            this.bannedWord = bannedWord;
            this.level = level;
            this.context = context;
        }

        public boolean isMatched() {
            return matched;
        }

        public boolean isCrossMessageMatch() {
            return crossMessageMatch;
        }

        public String getBannedWord() {
            return bannedWord;
        }

        public String getLevel() {
            return level;
        }

        public String getContext() {
            return context;
        }
    }
}