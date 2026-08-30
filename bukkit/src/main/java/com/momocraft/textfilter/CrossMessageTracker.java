package com.momocraft.textfilter;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrossMessageTracker {

    private final TextFilter plugin;
    private final ConcurrentHashMap<UUID, List<TrackingState>> trackingStates;

    public CrossMessageTracker(TextFilter plugin) {
        this.plugin = plugin;
        this.trackingStates = new ConcurrentHashMap<>();
    }

    /** 内部返回结构：fuzzy 前缀匹配的结果 —— 命中的 remaining 字符数 + 在传入文本内的索引。 */
    private static class FuzzyPrefixMatch {
        final int matchedLen;
        final int[] positionsInCurrent; // 在传入文本内的索引（按命中顺序）

        FuzzyPrefixMatch(int matchedLen, int[] positionsInCurrent) {
            this.matchedLen = matchedLen;
            this.positionsInCurrent = positionsInCurrent;
        }

        static final FuzzyPrefixMatch NONE = new FuzzyPrefixMatch(0, new int[0]);
    }

    /** 内部返回结构：suffix fuzzy 匹配的结果。 */
    private static class FuzzySuffixMatch {
        final boolean matched;
        final int[] positionsInCurrent;

        FuzzySuffixMatch(boolean matched, int[] positionsInCurrent) {
            this.matched = matched;
            this.positionsInCurrent = positionsInCurrent;
        }

        static final FuzzySuffixMatch NONE = new FuzzySuffixMatch(false, new int[0]);
    }

    public TrackingResult checkAndTrack(Player player, String text, String context) {
        if (text == null || text.isEmpty()) {
            cleanupExpired(player.getUniqueId());
            return null;
        }

        UUID playerId = player.getUniqueId();
        String strippedText = CharacterMapper.normalize(ColorCodeUtils.stripAllFormatting(text).toLowerCase());

        // 压缩连续重复字符（"傻1111逼" 视作 "傻1逼"），跨消息匹配与单消息压缩检测语义一致
        StringBuilder compressedBuilder = new StringBuilder();
        int[] compressedToOriginal = ColorCodeUtils.compressRepeatingChars(strippedText, compressedBuilder);
        String compressedText = compressedBuilder.toString();

        List<TrackingResult> results = new ArrayList<>();

        // ---- 1. 当前消息独立检测（反向提示去重：返回实际匹配文本）----
        for (Map.Entry<String, List<String>> entry : plugin.getConfigManager().getBannedWordsByLevel().entrySet()) {
            String level = entry.getKey();
            for (String bannedWord : entry.getValue()) {
                if (bannedWord == null || bannedWord.isEmpty()) continue;

                String matchedWord = matchBannedWord(strippedText, bannedWord, level);
                if (matchedWord != null) {
                    results.add(new TrackingResult(true, false, matchedWord, level, context, null));
                }
            }
        }

        // ---- 2. 走已有的 tracking state：带夹字 fuzzy 的前缀 / suffix 匹配（基于压缩文本）----
        List<TrackingState> states = trackingStates.get(playerId);
        List<TrackingState> toRemove = new ArrayList<>();

        if (states != null && !states.isEmpty()) {
            for (TrackingState state : states) {
                int levelChatboxGap = plugin.getConfigManager().getChatboxGapForLevel(state.level);
                CharGapLimits charGapLimits = plugin.getConfigManager().getMaxCharGapForLevel(state.level);
                boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();

                if (state.hasMatched) {
                    state.matchCounter++;
                    if (levelChatboxGap <= 0 || state.matchCounter > levelChatboxGap) {
                        toRemove.add(state);
                    } else {
                        // 匹配后 max-chatbox-gap 条内的继续追踪：做 fuzzy suffix 检测
                        String lowerBanned = CharacterMapper.normalize(state.bannedWord.toLowerCase());
                        FuzzySuffixMatch suffixM = fuzzyMatch ? fuzzySuffixMatch(compressedText, lowerBanned, charGapLimits)
                                                              : strictSuffixMatch(compressedText, lowerBanned);
                        String matchedWord = matchBannedWord(strippedText, state.bannedWord, state.level);
                        if (suffixM.matched || matchedWord != null) {
                            int[] positions;
                            if (suffixM.matched && suffixM.positionsInCurrent.length > 0) {
                                positions = mapToOriginal(suffixM.positionsInCurrent, compressedToOriginal);
                            } else if (matchedWord != null) {
                                positions = mapToOriginal(findAllSuffixPositions(compressedText, lowerBanned, charGapLimits, fuzzyMatch), compressedToOriginal);
                            } else {
                                positions = new int[0];
                            }
                            results.add(new TrackingResult(true, true, matchedWord != null ? matchedWord : state.bannedWord, state.level, context, positions));
                        }
                        state.lastUpdateTime = System.currentTimeMillis();
                    }
                } else {
                    String lowerBanned = CharacterMapper.normalize(state.bannedWord.toLowerCase());
                    String remaining = lowerBanned.substring(state.currentPosition);
                    FuzzyPrefixMatch pm = fuzzyMatch
                            ? fuzzyPrefixMatch(compressedText, remaining, charGapLimits)
                            : strictPrefixMatch(compressedText, remaining);

                    if (pm.matchedLen > 0) {
                        int newPosition = state.currentPosition + pm.matchedLen;
                        state.currentPosition = newPosition;
                        state.gapCounter = 0;
                        // 记住最后一次在当前消息中匹配到的位置（供触发时替换），映射回原始索引
                        int[] mappedPositions = mapToOriginal(pm.positionsInCurrent, compressedToOriginal);
                        state.lastMatchPositions = mappedPositions;

                        if (newPosition >= lowerBanned.length()) {
                            results.add(new TrackingResult(true, true, state.bannedWord, state.level, context, mappedPositions));
                            state.hasMatched = true;
                            state.matchCounter = 0;
                        }
                        state.lastUpdateTime = System.currentTimeMillis();
                    } else {
                        state.gapCounter++;
                        state.lastMatchPositions = new int[0];
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

        // ---- 3. 无现有命中时，为当前消息新建 partial tracking state（正向 + 反向，基于压缩文本）----
        if (results.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : plugin.getConfigManager().getBannedWordsByLevel().entrySet()) {
                String level = entry.getKey();
                int levelChatboxGap = plugin.getConfigManager().getChatboxGapForLevel(level);

                if (levelChatboxGap <= 0) {
                    continue;
                }

                boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
                CharGapLimits charGapLimits = plugin.getConfigManager().getMaxCharGapForLevel(level);
                boolean reverseEnabled = plugin.getConfigManager().isReverseMatchEnable()
                        && plugin.getConfigManager().isReverseMatchEnableForLevel(level);

                for (String bannedWord : entry.getValue()) {
                    if (bannedWord == null || bannedWord.isEmpty()) continue;

                    String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
                    if (lowerBanned.isEmpty()) continue;

                    // 追踪目标：正向词 + （启用反向时）反转词；回文词跳过重复目标
                    List<String> targets = new ArrayList<>();
                    targets.add(bannedWord);
                    if (reverseEnabled && lowerBanned.length() >= 2) {
                        String reversed = ColorCodeUtils.reverseWord(bannedWord);
                        if (!CharacterMapper.normalize(reversed.toLowerCase()).equals(lowerBanned)) {
                            targets.add(reversed);
                        }
                    }

                    // 为所有部分匹配的词都建 state（此前只给第一个匹配的词建，
                    // 若同级词表中其它同首字符词排在前面，会导致后面的词跨消息永远接不上）
                    for (String target : targets) {
                        String lowerTarget = CharacterMapper.normalize(target.toLowerCase());
                        char firstChar = lowerTarget.charAt(0);
                        int s = 0;
                        while (s < compressedText.length()) {
                            int idx = compressedText.indexOf(firstChar, s);
                            if (idx < 0) break;
                            String tail = compressedText.substring(idx);
                            FuzzyPrefixMatch pm = fuzzyMatch
                                    ? fuzzyPrefixMatch(tail, lowerTarget, charGapLimits)
                                    : strictPrefixMatch(tail, lowerTarget);
                            if (pm.matchedLen > 0 && pm.matchedLen < lowerTarget.length()) {
                                // tail 内索引平移回 compressedText 全局，再映射回 strippedText 原始索引
                                int[] globalPos = new int[pm.positionsInCurrent.length];
                                for (int k = 0; k < pm.positionsInCurrent.length; k++) {
                                    int cp = pm.positionsInCurrent[k] + idx;
                                    globalPos[k] = (cp >= 0 && cp < compressedToOriginal.length) ? compressedToOriginal[cp] : cp;
                                }
                                addTrackingState(playerId, target, level, pm.matchedLen, globalPos);
                                break;
                            }
                            s = idx + 1;
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

    // ------------------------------------------------------------------
    // Strict / Fuzzy prefix matching（传入文本为压缩后文本，返回压缩域索引）
    // ------------------------------------------------------------------

    /** 严格前缀匹配：仅从 text[0] 起逐字符对齐，遇不匹配就停。 */
    private static FuzzyPrefixMatch strictPrefixMatch(String text, String prefix) {
        List<Integer> pos = new ArrayList<>();
        int i = 0;
        while (i < text.length() && i < prefix.length() && text.charAt(i) == prefix.charAt(i)) {
            pos.add(i);
            i++;
        }
        int[] arr = new int[pos.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = pos.get(k);
        return new FuzzyPrefixMatch(pos.size(), arr);
    }

    /** Fuzzy 前缀匹配：允许按 chinese/english/others 三类计数夹字，任一类超过上限即放弃窗口，
     *  但只要能匹配到 remaining 的字符就继续 —— 符合"夹字也能跨消息接上"的需求。
     *  返回匹配到的 remaining 字符数，以及它们在 text 中的绝对索引（压缩域，供回映）。 */
    private static FuzzyPrefixMatch fuzzyPrefixMatch(String text, String remaining, CharGapLimits limits) {
        if (text == null || remaining == null || remaining.isEmpty()) return FuzzyPrefixMatch.NONE;
        int maxScanDist = remaining.length() + Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int maxEnd = Math.min(text.length(), maxScanDist);

        // outer：只从 remaining 首字符出现的位置启动，避免 O(n^2)
        char firstChar = remaining.charAt(0);
        int start = 0;
        while (start < maxEnd) {
            int firstIdx = text.indexOf(firstChar, start);
            if (firstIdx < 0 || firstIdx >= maxEnd) break;
            start = firstIdx;

            List<Integer> positions = new ArrayList<>();
            int textIdx = firstIdx;
            int remIdx = 0;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            int innerMaxEnd = Math.min(text.length(), firstIdx + maxScanDist);

            while (textIdx < innerMaxEnd && remIdx < remaining.length()) {
                if (text.charAt(textIdx) == remaining.charAt(remIdx)) {
                    positions.add(textIdx);
                    remIdx++;
                } else {
                    switch (CharacterMapper.classify(text.charAt(textIdx))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIdx++;
            }

            if (remIdx > 0) {
                int[] arr = new int[positions.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = positions.get(k);
                return new FuzzyPrefixMatch(remIdx, arr);
            }
            start = firstIdx + 1;
        }
        return FuzzyPrefixMatch.NONE;
    }

    // ------------------------------------------------------------------
    // Strict / Fuzzy suffix matching（用于 hasMatched=true 后继续追踪命中字符，压缩域索引）
    // ------------------------------------------------------------------

    private static FuzzySuffixMatch strictSuffixMatch(String text, String banned) {
        List<Integer> pos = new ArrayList<>();
        int ti = text.length() - 1;
        int bi = banned.length() - 1;
        while (ti >= 0 && bi >= 0 && text.charAt(ti) == banned.charAt(bi)) {
            pos.add(0, ti);
            ti--;
            bi--;
        }
        if (pos.isEmpty()) return FuzzySuffixMatch.NONE;
        int[] arr = new int[pos.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = pos.get(k);
        return new FuzzySuffixMatch(true, arr);
    }

    /** 从文本右端向左，按 fuzzy 规则匹配 banned 后缀；匹配到的字符索引（压缩域）存入返回值。 */
    private static FuzzySuffixMatch fuzzySuffixMatch(String text, String banned, CharGapLimits limits) {
        if (text == null || banned == null || banned.isEmpty()) return FuzzySuffixMatch.NONE;
        int maxScanDist = banned.length() + Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int textStart = Math.max(0, text.length() - maxScanDist);

        char lastChar = banned.charAt(banned.length() - 1);
        int p = text.length() - 1;
        while (p >= textStart) {
            int lastIdx = text.lastIndexOf(lastChar, p);
            if (lastIdx < textStart) break;
            p = lastIdx;

            List<Integer> positions = new ArrayList<>();
            int textIdx = p;
            int bi = banned.length() - 1;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            int scanLeftBound = Math.max(0, p - maxScanDist);

            while (textIdx >= scanLeftBound && bi >= 0) {
                if (text.charAt(textIdx) == banned.charAt(bi)) {
                    positions.add(0, textIdx);
                    bi--;
                } else {
                    switch (CharacterMapper.classify(text.charAt(textIdx))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIdx--;
            }

            if (!positions.isEmpty()) {
                int[] arr = new int[positions.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = positions.get(k);
                return new FuzzySuffixMatch(true, arr);
            }
            p = lastIdx - 1;
        }
        return FuzzySuffixMatch.NONE;
    }

    /** matchBannedWord 命中时用 fuzzy/strict 方式找出 banned 在 text（压缩域）中的末尾匹配索引（兜底）。 */
    private int[] findAllSuffixPositions(String text, String banned, CharGapLimits limits, boolean fuzzyMatch) {
        FuzzySuffixMatch m = fuzzyMatch ? fuzzySuffixMatch(text, banned, limits) : strictSuffixMatch(text, banned);
        return m.positionsInCurrent;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** 压缩文本索引 -> 原始 strippedText 索引 */
    private static int[] mapToOriginal(int[] compressedPositions, int[] compressedToOriginal) {
        if (compressedPositions == null || compressedPositions.length == 0) {
            return new int[0];
        }
        int[] out = new int[compressedPositions.length];
        for (int k = 0; k < out.length; k++) {
            int p = compressedPositions[k];
            out[k] = (p >= 0 && p < compressedToOriginal.length) ? compressedToOriginal[p] : p;
        }
        return out;
    }

    private void addTrackingState(UUID playerId, String bannedWord, String level, int currentPosition, int[] positions) {
        List<TrackingState> states = trackingStates.computeIfAbsent(playerId, k -> new ArrayList<>());
        // 同一玩家同一词同一等级只保留一个未完成的 partial state：
        // 避免重复消息（如连发多条"民"）堆积重复 state 造成重复提示
        for (TrackingState existing : states) {
            if (!existing.hasMatched && existing.bannedWord.equals(bannedWord) && existing.level.equals(level)) {
                existing.lastUpdateTime = System.currentTimeMillis();
                if (currentPosition > existing.currentPosition) {
                    existing.currentPosition = currentPosition;
                    existing.lastMatchPositions = positions == null ? new int[0] : Arrays.copyOf(positions, positions.length);
                }
                return;
            }
        }
        states.add(new TrackingState(bannedWord, level, currentPosition, positions));
    }

    /** 返回实际匹配到的违禁词文本：正向命中返回配置词，反向命中返回反转词，未命中返回 null。 */
    private String matchBannedWord(String text, String bannedWord, String level) {
        ConfigManager config = plugin.getConfigManager();
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

    // ------------------------------------------------------------------
    // Nested classes
    // ------------------------------------------------------------------

    public static class TrackingState {
        final String bannedWord;
        final String level;
        int currentPosition;
        int gapCounter;
        int matchCounter;
        boolean hasMatched;
        long lastUpdateTime;
        /** 最近一次（current 消息中）命中 remaining 前缀的字符在 strippedText 中的索引（已回映）。 */
        int[] lastMatchPositions;

        TrackingState(String bannedWord, String level, int currentPosition) {
            this(bannedWord, level, currentPosition, new int[0]);
        }

        TrackingState(String bannedWord, String level, int currentPosition, int[] positions) {
            this.bannedWord = bannedWord;
            this.level = level;
            this.currentPosition = currentPosition;
            this.gapCounter = 0;
            this.matchCounter = 0;
            this.hasMatched = false;
            this.lastUpdateTime = System.currentTimeMillis();
            this.lastMatchPositions = positions == null ? new int[0] : Arrays.copyOf(positions, positions.length);
        }
    }

    public static class TrackingResult {
        private final boolean matched;
        private final boolean crossMessageMatch;
        private final String bannedWord;
        private final String level;
        private final String context;
        /** 跨消息命中时：当前消息（strippedText 域）内应被打码的字符索引；可能为空（需走 listener 侧 fallback 替换）。
         *  非跨消息命中（独立检测）时为 null。 */
        private final int[] matchedPositionsInCurrent;

        public TrackingResult(boolean matched, boolean crossMessageMatch, String bannedWord, String level, String context) {
            this(matched, crossMessageMatch, bannedWord, level, context, null);
        }

        public TrackingResult(boolean matched, boolean crossMessageMatch, String bannedWord, String level, String context,
                              int[] matchedPositionsInCurrent) {
            this.matched = matched;
            this.crossMessageMatch = crossMessageMatch;
            this.bannedWord = bannedWord;
            this.level = level;
            this.context = context;
            this.matchedPositionsInCurrent = matchedPositionsInCurrent;
        }

        public boolean isMatched() { return matched; }
        public boolean isCrossMessageMatch() { return crossMessageMatch; }
        public String getBannedWord() { return bannedWord; }
        public String getLevel() { return level; }
        public String getContext() { return context; }

        /** 命中索引（strippedText 域），若 tracker 侧未提供则返回 null。 */
        public int[] getMatchedPositionsInCurrent() { return matchedPositionsInCurrent; }
    }
}
