package com.momocraft.textfilter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorCodeUtils {

    private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[a-zA-Z0-9_]+(:[^>]*)?>");
    private static final Pattern AND_X_COLOR_CODE = Pattern.compile("&x&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])");
    private static final Pattern LEGACY_COLOR_CODES = Pattern.compile("[§&][0-9a-fA-FklmnorxX]");
    private static final Pattern MINIMESSAGE_COLOR_TAGS = Pattern.compile("<#[0-9a-fA-F]{3,6}>");

    public static class Segment {
        final String content;
        final boolean isTag;

        Segment(String content, boolean isTag) {
            this.content = content;
            this.isTag = isTag;
        }
    }

    public static List<Segment> splitByMiniMessageTags(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }

        Matcher matcher = MINIMESSAGE_TAG.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                segments.add(new Segment(text.substring(lastEnd, matcher.start()), false));
            }
            segments.add(new Segment(matcher.group(), true));
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            segments.add(new Segment(text.substring(lastEnd), false));
        }

        return segments;
    }

    public static String convertAndXColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = AND_X_COLOR_CODE.matcher(text);
        return matcher.replaceAll(mr -> {
            String color = "#" + mr.group(1) + mr.group(2) + mr.group(3) + mr.group(4) + mr.group(5) + mr.group(6);
            return "<" + color + ">";
        });
    }

    public static String stripAllFormatting(String text) {
        if (text == null) return null;
        TextProcessor processor = new TextProcessor(text);
        return processor.getProcessedText();
    }

    public static String normalizeText(String text) {
        if (text == null) return null;
        text = stripAllFormatting(text);
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return text.toLowerCase();
    }

    public static boolean containsBannedWord(String text, String bannedWord, boolean fuzzyMatch, CharGapLimits limits, Iterable<String> whitelist) {
        if (text == null || bannedWord == null || bannedWord.isEmpty()) {
            return false;
        }

        String normalizedText = normalizeText(text);
        String normalizedBanned = normalizeText(bannedWord);

        if (normalizedBanned.isEmpty()) {
            return false;
        }

        if (!fuzzyMatch) {
            int index = normalizedText.indexOf(normalizedBanned);
            while (index != -1) {
                if (!isInWhitelistRange(index, index + normalizedBanned.length(), normalizedText, whitelist)) {
                    return true;
                }
                index = normalizedText.indexOf(normalizedBanned, index + 1);
            }
            return false;
        }

        return fuzzyContainsBannedWord(normalizedText, normalizedBanned, limits, whitelist);
    }

    private static boolean fuzzyContainsBannedWord(String text, String bannedWord, CharGapLimits limits, Iterable<String> whitelist) {
        int maxGap = Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int maxScanDist = bannedWord.length() + maxGap;
        // 只从违禁词首字符出现的位置开始扫描，避免 O(n^2) 卡死 watchdog
        int start = 0;
        char firstChar = bannedWord.charAt(0);
        int maxStart = text.length() - bannedWord.length();

        while (start <= maxStart) {
            int idx = text.indexOf(firstChar, start);
            if (idx < 0 || idx > maxStart) break;
            start = idx;

            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            int maxEnd = Math.min(text.length(), start + maxScanDist);

            while (textIndex < maxEnd && bannedIndex < bannedWord.length()) {
                if (text.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else {
                    switch (CharacterMapper.classify(text.charAt(textIndex))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    // 任意一类间隔字符超过对应上限即放弃该窗口（放行）
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIndex++;
            }

            if (matchedEnd > 0) {
                if (!isInWhitelistRange(start, matchedEnd, text, whitelist)) {
                    return true;
                }
            }
            start = idx + 1;
        }

        return fuzzyContainsWithCompression(text, bannedWord, limits, whitelist);
    }

    private static boolean fuzzyContainsWithCompression(String text, String bannedWord, CharGapLimits limits, Iterable<String> whitelist) {
        StringBuilder compressedBuilder = new StringBuilder();
        int[] compressedToOriginal = compressRepeatingChars(text, compressedBuilder);
        String compressedText = compressedBuilder.toString();
        if (compressedText.length() < bannedWord.length()) return false;

        int maxGap = Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int maxScanDist = bannedWord.length() + maxGap;
        // 只从首字符出现位置开始扫描
        int start = 0;
        char firstChar = bannedWord.charAt(0);
        int maxStart = compressedText.length() - bannedWord.length();

        while (start <= maxStart) {
            int idx = compressedText.indexOf(firstChar, start);
            if (idx < 0 || idx > maxStart) break;
            start = idx;

            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            int maxEnd = Math.min(compressedText.length(), start + maxScanDist);

            while (textIndex < maxEnd && bannedIndex < bannedWord.length()) {
                if (compressedText.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else {
                    switch (CharacterMapper.classify(compressedText.charAt(textIndex))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIndex++;
            }

            if (matchedEnd > 0) {
                int origStart = compressedToOriginal[start];
                int origEnd = compressedToOriginal[matchedEnd - 1] + 1;
                if (!isInWhitelistRange(origStart, origEnd, text, whitelist)) {
                    return true;
                }
            }
            start = idx + 1;
        }

        return false;
    }

    public static int[] compressRepeatingChars(String text, StringBuilder compressed) {
        List<Integer> indices = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            compressed.append(c);
            indices.add(i);
            while (i + 1 < text.length() && text.charAt(i + 1) == c) {
                i++;
            }
            i++;
        }
        int[] result = new int[indices.size()];
        for (int j = 0; j < indices.size(); j++) {
            result[j] = indices.get(j);
        }
        return result;
    }

    /** 判断违禁词区间 [start, end) 是否完全被白名单保护区域覆盖。
     *  先收集所有白名单词的匹配区间，合并相邻/重叠的区间，再判断违禁词是否落入任一合并后的区间。
     *  这样，多个相邻白名单词拼接（如 tps + bar -> tpsbar）会被视作一个完整的保护区域。 */
    private static boolean isInWhitelistRange(int start, int end, String text, Iterable<String> whitelist) {
        if (whitelist == null) {
            return false;
        }

        java.util.List<int[]> ranges = new java.util.ArrayList<>();
        for (String whiteWord : whitelist) {
            if (whiteWord == null) continue;
            String lowerWhite = normalizeText(whiteWord);
            if (lowerWhite.isEmpty()) continue;
            int whiteStart = 0;
            while ((whiteStart = text.indexOf(lowerWhite, whiteStart)) != -1) {
                ranges.add(new int[]{whiteStart, whiteStart + lowerWhite.length()});
                whiteStart++;
            }
        }
        if (ranges.isEmpty()) {
            return false;
        }

        // 合并相邻或重叠的区间
        ranges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
        java.util.List<int[]> merged = new java.util.ArrayList<>();
        int[] cur = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            int[] next = ranges.get(i);
            if (next[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);

        for (int[] range : merged) {
            if (start >= range[0] && end <= range[1]) {
                return true;
            }
        }
        return false;
    }

    public static String replaceBannedWord(String text, String bannedWord, boolean fuzzyMatch, CharGapLimits limits, Iterable<String> whitelist) {
        if (text == null || bannedWord == null || bannedWord.isEmpty()) {
            return text;
        }

        String normalizedText = normalizeText(text);
        String normalizedBanned = normalizeText(bannedWord);

        if (normalizedBanned.isEmpty()) {
            return text;
        }

        boolean[] toReplace = new boolean[normalizedText.length()];

        if (!fuzzyMatch) {
            int index = 0;
            while ((index = normalizedText.indexOf(normalizedBanned, index)) != -1) {
                if (!isInWhitelistRange(index, index + normalizedBanned.length(), normalizedText, whitelist)) {
                    for (int i = 0; i < normalizedBanned.length(); i++) {
                        toReplace[index + i] = true;
                    }
                }
                index += 1;
            }
        } else {
            List<MatchCandidate> candidates = new ArrayList<>();
            collectFuzzyMatches(normalizedText, normalizedBanned, limits, whitelist, bannedWord, "default", candidates);
            collectFuzzyMatchesWithCompression(normalizedText, normalizedBanned, limits, whitelist, bannedWord, "default", candidates);
            for (MatchCandidate candidate : candidates) {
                for (int pos : candidate.positions) {
                    toReplace[pos] = true;
                }
            }
        }

        TextProcessor processor = new TextProcessor(text);
        return processor.replaceInOriginalWithMask(toReplace, "*");
    }

    public static String reverseWord(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return new StringBuilder(word).reverse().toString();
    }

    public static String filterAllBannedWords(String text, Iterable<String> bannedWords,
            boolean fuzzyMatch, CharGapLimits limits, boolean reverseMatch, Iterable<String> whitelist) {
        if (text == null) {
            return null;
        }
        Map<String, List<String>> bannedWordsByLevel = new HashMap<>();
        List<String> wordList = new ArrayList<>();
        bannedWords.forEach(word -> {
            if (word != null && !word.isEmpty()) {
                wordList.add(word);
            }
        });
        bannedWordsByLevel.put("default", wordList);
        Map<String, CharGapLimits> limitsByLevel = new HashMap<>();
        limitsByLevel.put("default", limits);
        Map<String, Boolean> reverseMatchByLevel = new HashMap<>();
        reverseMatchByLevel.put("default", reverseMatch);
        BannedWordDetection detection = filterAllBannedWordsWithDetection(text, bannedWordsByLevel,
                fuzzyMatch, limits, limitsByLevel, reverseMatch, reverseMatchByLevel, whitelist);
        return detection.getFilteredText();
    }

    public static String filterAllBannedWords(String text, Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, CharGapLimits defaultLimits, Map<String, CharGapLimits> limitsByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return null;
        }
        BannedWordDetection detection = filterAllBannedWordsWithDetection(text, bannedWordsByLevel,
                fuzzyMatch, defaultLimits, limitsByLevel, reverseMatch, reverseMatchByLevel, whitelist);
        return detection.getFilteredText();
    }

    public static BannedWordDetection filterAllBannedWordsWithDetection(String text,
            Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, CharGapLimits defaultLimits, Map<String, CharGapLimits> limitsByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return new BannedWordDetection(null);
        }

        TextProcessor processor = new TextProcessor(text);
        String visibleText = processor.getProcessedText();
        // NFKC 可能将单字符展开为多字符（如 "…"(U+2026) -> "...")，导致规范化文本长度
        // 与可见文本不一致。必须记录 规范化索引 -> 可见索引 映射，否则替换时星号会错位。
        NormResult norm = normalizeWithMapping(visibleText);
        String processedText = norm.text;
        boolean[] toReplace = new boolean[processedText.length()];
        BannedWordDetection result = new BannedWordDetection(text);

        // 收集所有候选匹配区间（精确匹配 + 模糊匹配 + 压缩匹配 + 反向匹配），
        // 最后统一做"非重叠"贪心选择：重叠的区间只保留最左优先的一个，
        // 避免 "你他妈" 同时命中 "你妈" 与 "他妈" 重复报告/重复打码。
        List<MatchCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : bannedWordsByLevel.entrySet()) {
            String level = entry.getKey();
            CharGapLimits limits = limitsByLevel.getOrDefault(level, defaultLimits);
            boolean levelReverseMatch = reverseMatch && (reverseMatchByLevel == null ||
                    reverseMatchByLevel.getOrDefault(level, true));

            for (String bannedWord : entry.getValue()) {
                if (bannedWord == null || bannedWord.isEmpty()) continue;

                String normalizedBanned = CharacterMapper.normalize(java.text.Normalizer.normalize(stripAllFormatting(bannedWord), java.text.Normalizer.Form.NFKC).toLowerCase());
                if (normalizedBanned.isEmpty()) continue;

                if (!fuzzyMatch) {
                    collectExactMatches(processedText, normalizedBanned, whitelist, bannedWord, level, candidates);
                } else {
                    collectFuzzyMatches(processedText, normalizedBanned, limits, whitelist, bannedWord, level, candidates);
                    collectFuzzyMatchesWithCompression(processedText, normalizedBanned, limits, whitelist, bannedWord, level, candidates);
                }

                if (levelReverseMatch && bannedWord.length() >= 2) {
                    String reversedWord = reverseWord(bannedWord);
                    String normalizedReversed = CharacterMapper.normalize(java.text.Normalizer.normalize(stripAllFormatting(reversedWord), java.text.Normalizer.Form.NFKC).toLowerCase());
                    if (normalizedReversed.isEmpty()) continue;

                    if (!fuzzyMatch) {
                        collectExactMatches(processedText, normalizedReversed, whitelist, reversedWord, level, candidates);
                    } else {
                        collectFuzzyMatches(processedText, normalizedReversed, limits, whitelist, reversedWord, level, candidates);
                        collectFuzzyMatchesWithCompression(processedText, normalizedReversed, limits, whitelist, reversedWord, level, candidates);
                    }
                }
            }
        }

        // 非重叠贪心选择：按起点升序（同起点取覆盖更长者）排列，逐个选取不重叠的区间
        candidates.sort((a, b) -> a.start != b.start
                ? Integer.compare(a.start, b.start)
                : Integer.compare(b.end, a.end));
        int lastEnd = -1;
        for (MatchCandidate candidate : candidates) {
            if (candidate.start >= lastEnd) {
                for (int pos : candidate.positions) {
                    toReplace[pos] = true;
                }
                result.addDetectedWord(candidate.word, candidate.level);
                lastEnd = candidate.end;
            }
        }

        // 将规范化文本索引的 mask 转换回可见文本索引（NFKC 展开后二者长度不同，直接映射会错位）
        boolean[] visibleMask = new boolean[visibleText.length()];
        for (int j = 0; j < toReplace.length; j++) {
            if (toReplace[j]) {
                int vi = norm.mapping.get(j);
                if (vi >= 0 && vi < visibleMask.length) {
                    visibleMask[vi] = true;
                }
            }
        }

        String filtered = processor.replaceInOriginalWithMask(visibleMask, "*");
        // 如果没有违禁词，返回原始文本（未预处理）；否则返回预处理后的文本（带替换）
        if (filtered.equals(processor.getOriginalText())) {
            result.setFilteredText(text);
        } else {
            result.setFilteredText(filtered);
        }
        return result;
    }

    /** 收集精确（非模糊）匹配区间 */
    private static void collectExactMatches(String text, String bannedWord, Iterable<String> whitelist,
            String reportWord, String level, List<MatchCandidate> out) {
        int index = 0;
        while ((index = text.indexOf(bannedWord, index)) != -1) {
            if (!isInWhitelistRange(index, index + bannedWord.length(), text, whitelist)) {
                List<Integer> positions = new ArrayList<>();
                for (int i = 0; i < bannedWord.length(); i++) {
                    positions.add(index + i);
                }
                out.add(new MatchCandidate(index, index + bannedWord.length(), positions, reportWord, level));
            }
            index += 1;
        }
    }

    /** 收集模糊匹配区间（按字符类型分别计数间隔字符，任一类超限即放行） */
    private static void collectFuzzyMatches(String text, String bannedWord, CharGapLimits limits,
            Iterable<String> whitelist, String reportWord, String level, List<MatchCandidate> out) {
        int maxGap = Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int maxScanDist = bannedWord.length() + maxGap;
        // 只从违禁词首字符出现的位置开始扫描，避免 O(n^2) 卡死 watchdog
        int start = 0;
        char firstChar = bannedWord.charAt(0);
        int maxStart = text.length() - bannedWord.length();

        while (start <= maxStart) {
            int idx = text.indexOf(firstChar, start);
            if (idx < 0 || idx > maxStart) break;
            start = idx;

            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            List<Integer> matchedPositions = new ArrayList<>();
            int maxEnd = Math.min(text.length(), start + maxScanDist);

            while (textIndex < maxEnd && bannedIndex < bannedWord.length()) {
                if (text.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    matchedPositions.add(textIndex);
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else {
                    switch (CharacterMapper.classify(text.charAt(textIndex))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    // 任意一类间隔字符超过对应上限即放弃该窗口（放行）
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIndex++;
            }

            if (matchedEnd > 0 && !isInWhitelistRange(start, matchedEnd, text, whitelist)) {
                out.add(new MatchCandidate(start, matchedEnd, matchedPositions, reportWord, level));
            }
            start = idx + 1;
        }
    }

    /** 收集压缩重复字符后的模糊匹配区间（"傻1111逼" 视作 "傻逼"） */
    private static void collectFuzzyMatchesWithCompression(String text, String bannedWord, CharGapLimits limits,
            Iterable<String> whitelist, String reportWord, String level, List<MatchCandidate> out) {
        StringBuilder compressedBuilder = new StringBuilder();
        int[] compressedToOriginal = compressRepeatingChars(text, compressedBuilder);
        String compressedText = compressedBuilder.toString();
        if (compressedText.length() < bannedWord.length()) return;

        int maxGap = Math.max(limits.chinese, Math.max(limits.english, limits.others));
        int maxScanDist = bannedWord.length() + maxGap;
        // 只从首字符出现位置开始扫描
        int start = 0;
        char firstChar = bannedWord.charAt(0);
        int maxStart = compressedText.length() - bannedWord.length();

        while (start <= maxStart) {
            int idx = compressedText.indexOf(firstChar, start);
            if (idx < 0 || idx > maxStart) break;
            start = idx;

            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;
            int chineseGap = 0, englishGap = 0, othersGap = 0;
            List<Integer> matchedPositions = new ArrayList<>();
            int maxEnd = Math.min(compressedText.length(), start + maxScanDist);

            while (textIndex < maxEnd && bannedIndex < bannedWord.length()) {
                if (compressedText.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    matchedPositions.add(compressedToOriginal[textIndex]);
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else {
                    switch (CharacterMapper.classify(compressedText.charAt(textIndex))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                }
                textIndex++;
            }

            if (matchedEnd > 0) {
                int origStart = compressedToOriginal[start];
                int origEnd = compressedToOriginal[matchedEnd - 1] + 1;
                if (!isInWhitelistRange(origStart, origEnd, text, whitelist)) {
                    out.add(new MatchCandidate(origStart, origEnd, matchedPositions, reportWord, level));
                }
            }
            start = idx + 1;
        }
    }

    /** 候选匹配区间：用于非重叠贪心选择 */
    private static class MatchCandidate {
        final int start;      // 区间起点（processedText 索引）
        final int end;        // 区间终点（不含）
        final List<Integer> positions; // 需要打码的字符位置（仅违禁词字符本身，不含间隔字符）
        final String word;    // 上报的违禁词
        final String level;

        MatchCandidate(int start, int end, List<Integer> positions, String word, String level) {
            this.start = start;
            this.end = end;
            this.positions = positions;
            this.word = word;
            this.level = level;
        }
    }

    /**
     * 规范化文本并记录 规范化索引 -> 可见索引 映射。
     * NFKC 规范化可能将单字符展开为多个字符（如 "…"(U+2026) 展开为 "..."），
     * 导致规范化后的文本长度与可见文本不同；通过该映射可将匹配位置准确还原到可见文本。
     */
    private static NormResult normalizeWithMapping(String visibleText) {
        StringBuilder sb = new StringBuilder(visibleText.length());
        List<Integer> mapping = new ArrayList<>();
        for (int vi = 0; vi < visibleText.length(); vi++) {
            String norm = CharacterMapper.normalize(java.text.Normalizer.normalize(
                    String.valueOf(visibleText.charAt(vi)), java.text.Normalizer.Form.NFKC).toLowerCase());
            if (norm.isEmpty()) {
                mapping.add(vi);
                continue;
            }
            for (int k = 0; k < norm.length(); k++) {
                mapping.add(vi);
            }
            sb.append(norm);
        }
        return new NormResult(sb.toString(), mapping);
    }

    private static class NormResult {
        final String text;
        final List<Integer> mapping;

        NormResult(String text, List<Integer> mapping) {
            this.text = text;
            this.mapping = mapping;
        }
    }

    /**
     * 对文本执行违禁词检测，并在首次替换后反复复核处理后的文本，直到结果稳定。
     * 例如 "傻傻逼逼" 经一次替换可能得到 "*傻*逼"，此时 "傻*逼" 仍可匹配违禁词，
     * 需再次检测直至无违禁词残留。
     * 由于 {@link TextProcessor#replaceInOriginalWithMask} 为 1:1 字符替换，
     * 文本长度保持不变，位置映射不受影响。
     */
    public static BannedWordDetection filterAllWithRecheck(String text,
            Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, CharGapLimits defaultLimits, Map<String, CharGapLimits> limitsByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return new BannedWordDetection(null);
        }

        BannedWordDetection detection = filterAllBannedWordsWithDetection(text, bannedWordsByLevel,
                fuzzyMatch, defaultLimits, limitsByLevel, reverseMatch, reverseMatchByLevel, whitelist);
        String filtered = detection.getFilteredText();
        if (filtered == null || filtered.equals(text)) {
            return detection;
        }

        // 反复核处理后的文本，最多 5 次以防死循环
        for (int i = 0; i < 5; i++) {
            BannedWordDetection recheck = filterAllBannedWordsWithDetection(filtered, bannedWordsByLevel,
                    fuzzyMatch, defaultLimits, limitsByLevel, reverseMatch, reverseMatchByLevel, whitelist);
            String next = recheck.getFilteredText();
            if (next.equals(filtered)) {
                break;
            }
            for (BannedWordDetection.BannedWordInfo info : recheck.getDetectedWords()) {
                detection.addDetectedWord(info.getWord(), info.getLevel());
            }
            filtered = next;
        }

        detection.setFilteredText(filtered);
        return detection;
    }

    public static boolean containsAnyBannedWord(String text, Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, CharGapLimits defaultLimits, Map<String, CharGapLimits> limitsByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return false;
        }

        for (Map.Entry<String, List<String>> entry : bannedWordsByLevel.entrySet()) {
            String level = entry.getKey();
            CharGapLimits limits = limitsByLevel.getOrDefault(level, defaultLimits);
            boolean levelReverseMatch = reverseMatch && (reverseMatchByLevel == null ||
                    reverseMatchByLevel.getOrDefault(level, true));

            for (String bannedWord : entry.getValue()) {
                if (bannedWord != null && !bannedWord.isEmpty()) {
                    if (containsBannedWord(text, bannedWord, fuzzyMatch, limits, whitelist)) {
                        return true;
                    }

                    if (levelReverseMatch && bannedWord.length() >= 2) {
                        String reversedWord = reverseWord(bannedWord);
                        if (containsBannedWord(text, reversedWord, fuzzyMatch, limits, whitelist)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
