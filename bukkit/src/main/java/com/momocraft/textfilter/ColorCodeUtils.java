package com.momocraft.textfilter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorCodeUtils {

    private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[^>]+>");
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

    public static boolean containsBannedWord(String text, String bannedWord, boolean fuzzyMatch, int maxCharGap, Iterable<String> whitelist) {
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

        return fuzzyContainsBannedWord(normalizedText, normalizedBanned, maxCharGap, whitelist);
    }

    private static boolean fuzzyContainsBannedWord(String text, String bannedWord, int maxCharGap, Iterable<String> whitelist) {
        for (int start = 0; start <= text.length() - bannedWord.length(); start++) {
            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;

            while (textIndex < text.length() && bannedIndex < bannedWord.length()) {
                if (text.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else if (textIndex - start - bannedIndex >= maxCharGap) {
                    break;
                }
                textIndex++;
            }

            if (matchedEnd > 0) {
                if (!isInWhitelistRange(start, matchedEnd, text, whitelist)) {
                    return true;
                }
            }
        }

        return fuzzyContainsWithCompression(text, bannedWord, maxCharGap, whitelist);
    }

    private static boolean fuzzyContainsWithCompression(String text, String bannedWord, int maxCharGap, Iterable<String> whitelist) {
        StringBuilder compressedBuilder = new StringBuilder();
        int[] compressedToOriginal = compressRepeatingChars(text, compressedBuilder);
        String compressedText = compressedBuilder.toString();

        for (int start = 0; start <= compressedText.length() - bannedWord.length(); start++) {
            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;

            while (textIndex < compressedText.length() && bannedIndex < bannedWord.length()) {
                if (compressedText.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else if (textIndex - start - bannedIndex >= maxCharGap) {
                    break;
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
        }

        return false;
    }

    private static int[] compressRepeatingChars(String text, StringBuilder compressed) {
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

    private static boolean isInWhitelistRange(int start, int end, String text, Iterable<String> whitelist) {
        if (whitelist == null) {
            return false;
        }
        for (String whiteWord : whitelist) {
            if (whiteWord == null) continue;
            String lowerWhite = normalizeText(whiteWord);
            if (lowerWhite.isEmpty()) continue;
            int whiteStart = 0;
            while ((whiteStart = text.indexOf(lowerWhite, whiteStart)) != -1) {
                int whiteEnd = whiteStart + lowerWhite.length();
                if (start >= whiteStart && end <= whiteEnd) {
                    return true;
                }
                whiteStart++;
            }
        }
        return false;
    }

    public static String replaceBannedWord(String text, String bannedWord, boolean fuzzyMatch, int maxCharGap, Iterable<String> whitelist) {
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
            findFuzzyMatches(normalizedText, normalizedBanned, maxCharGap, whitelist, toReplace);
            findFuzzyMatchesWithCompression(normalizedText, normalizedBanned, maxCharGap, whitelist, toReplace);
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

    public static String filterAllBannedWords(String text, Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, int defaultMaxCharGap, Map<String, Integer> maxCharGapByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return null;
        }

        String normalizedText = normalizeText(text);
        boolean[] toReplace = new boolean[normalizedText.length()];

        for (Map.Entry<String, List<String>> entry : bannedWordsByLevel.entrySet()) {
            String level = entry.getKey();
            int maxCharGap = maxCharGapByLevel.getOrDefault(level, defaultMaxCharGap);
            boolean levelReverseMatch = reverseMatch && (reverseMatchByLevel == null ||
                    reverseMatchByLevel.getOrDefault(level, true));

            for (String bannedWord : entry.getValue()) {
                if (bannedWord == null || bannedWord.isEmpty()) continue;

                String normalizedBanned = normalizeText(bannedWord);
                if (normalizedBanned.isEmpty()) continue;

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
                    findFuzzyMatches(normalizedText, normalizedBanned, maxCharGap, whitelist, toReplace);
                    findFuzzyMatchesWithCompression(normalizedText, normalizedBanned, maxCharGap, whitelist, toReplace);
                }

                if (levelReverseMatch && bannedWord.length() >= 2) {
                    String reversedWord = reverseWord(bannedWord);
                    String normalizedReversed = normalizeText(reversedWord);
                    if (normalizedReversed.isEmpty()) continue;

                    if (!fuzzyMatch) {
                        int index = 0;
                        while ((index = normalizedText.indexOf(normalizedReversed, index)) != -1) {
                            if (!isInWhitelistRange(index, index + normalizedReversed.length(), normalizedText, whitelist)) {
                                for (int i = 0; i < normalizedReversed.length(); i++) {
                                    toReplace[index + i] = true;
                                }
                            }
                            index += 1;
                        }
                    } else {
                        findFuzzyMatches(normalizedText, normalizedReversed, maxCharGap, whitelist, toReplace);
                        findFuzzyMatchesWithCompression(normalizedText, normalizedReversed, maxCharGap, whitelist, toReplace);
                    }
                }
            }
        }

        TextProcessor processor = new TextProcessor(text);
        return processor.replaceInOriginalWithMask(toReplace, "*");
    }

    public static BannedWordDetection filterAllBannedWordsWithDetection(String text,
            Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, int defaultMaxCharGap, Map<String, Integer> maxCharGapByLevel,
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

        for (Map.Entry<String, List<String>> entry : bannedWordsByLevel.entrySet()) {
            String level = entry.getKey();
            int maxCharGap = maxCharGapByLevel.getOrDefault(level, defaultMaxCharGap);
            boolean levelReverseMatch = reverseMatch && (reverseMatchByLevel == null ||
                    reverseMatchByLevel.getOrDefault(level, true));

            for (String bannedWord : entry.getValue()) {
                if (bannedWord == null || bannedWord.isEmpty()) continue;

                String normalizedBanned = CharacterMapper.normalize(java.text.Normalizer.normalize(stripAllFormatting(bannedWord), java.text.Normalizer.Form.NFKC).toLowerCase());
                if (normalizedBanned.isEmpty()) continue;

                boolean matched = false;

                if (!fuzzyMatch) {
                    int index = 0;
                    while ((index = processedText.indexOf(normalizedBanned, index)) != -1) {
                        if (!isInWhitelistRange(index, index + normalizedBanned.length(), processedText, whitelist)) {
                            for (int i = 0; i < normalizedBanned.length(); i++) {
                                toReplace[index + i] = true;
                            }
                            matched = true;
                        }
                        index += 1;
                    }
                } else {
                    if (findFuzzyMatches(processedText, normalizedBanned, maxCharGap, whitelist, toReplace)) {
                        matched = true;
                    }
                    if (findFuzzyMatchesWithCompression(processedText, normalizedBanned, maxCharGap, whitelist, toReplace)) {
                        matched = true;
                    }
                }

                if (matched) {
                    result.addDetectedWord(bannedWord, level);
                }

                if (levelReverseMatch && bannedWord.length() >= 2) {
                    String reversedWord = reverseWord(bannedWord);
                    String normalizedReversed = CharacterMapper.normalize(java.text.Normalizer.normalize(stripAllFormatting(reversedWord), java.text.Normalizer.Form.NFKC).toLowerCase());
                    if (normalizedReversed.isEmpty()) continue;

                    boolean reversedMatched = false;

                    if (!fuzzyMatch) {
                        int index = 0;
                        while ((index = processedText.indexOf(normalizedReversed, index)) != -1) {
                            if (!isInWhitelistRange(index, index + normalizedReversed.length(), processedText, whitelist)) {
                                for (int i = 0; i < normalizedReversed.length(); i++) {
                                    toReplace[index + i] = true;
                                }
                                reversedMatched = true;
                            }
                            index += 1;
                        }
                    } else {
                        if (findFuzzyMatches(processedText, normalizedReversed, maxCharGap, whitelist, toReplace)) {
                            reversedMatched = true;
                        }
                        if (findFuzzyMatchesWithCompression(processedText, normalizedReversed, maxCharGap, whitelist, toReplace)) {
                            reversedMatched = true;
                        }
                    }

                    if (reversedMatched) {
                        result.addDetectedWord(reversedWord, level);
                    }
                }
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
            boolean fuzzyMatch, int defaultMaxCharGap, Map<String, Integer> maxCharGapByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return new BannedWordDetection(null);
        }

        BannedWordDetection detection = filterAllBannedWordsWithDetection(text, bannedWordsByLevel,
                fuzzyMatch, defaultMaxCharGap, maxCharGapByLevel, reverseMatch, reverseMatchByLevel, whitelist);
        String filtered = detection.getFilteredText();
        if (filtered == null || filtered.equals(text)) {
            return detection;
        }

        // 反复核处理后的文本，最多 5 次以防死循环
        for (int i = 0; i < 5; i++) {
            BannedWordDetection recheck = filterAllBannedWordsWithDetection(filtered, bannedWordsByLevel,
                    fuzzyMatch, defaultMaxCharGap, maxCharGapByLevel, reverseMatch, reverseMatchByLevel, whitelist);
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

    private static boolean findFuzzyMatches(String text, String bannedWord, int maxCharGap, Iterable<String> whitelist, boolean[] toReplace) {
        boolean matched = false;
        for (int start = 0; start <= text.length() - bannedWord.length(); start++) {
            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;
            List<Integer> matchedPositions = new ArrayList<>();

            while (textIndex < text.length() && bannedIndex < bannedWord.length()) {
                if (text.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    matchedPositions.add(textIndex);
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else if (textIndex - start - bannedIndex >= maxCharGap) {
                    break;
                }
                textIndex++;
            }

            if (matchedEnd > 0) {
                if (!isInWhitelistRange(start, matchedEnd, text, whitelist)) {
                    for (int pos : matchedPositions) {
                        toReplace[pos] = true;
                    }
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean findFuzzyMatchesWithCompression(String text, String bannedWord, int maxCharGap, Iterable<String> whitelist, boolean[] toReplace) {
        boolean matched = false;
        StringBuilder compressedBuilder = new StringBuilder();
        int[] compressedToOriginal = compressRepeatingChars(text, compressedBuilder);
        String compressedText = compressedBuilder.toString();

        for (int start = 0; start <= compressedText.length() - bannedWord.length(); start++) {
            int textIndex = start;
            int bannedIndex = 0;
            int matchedEnd = -1;
            List<Integer> matchedPositions = new ArrayList<>();

            while (textIndex < compressedText.length() && bannedIndex < bannedWord.length()) {
                if (compressedText.charAt(textIndex) == bannedWord.charAt(bannedIndex)) {
                    matchedPositions.add(compressedToOriginal[textIndex]);
                    bannedIndex++;
                    if (bannedIndex == bannedWord.length()) {
                        matchedEnd = textIndex + 1;
                        break;
                    }
                } else if (textIndex - start - bannedIndex >= maxCharGap) {
                    break;
                }
                textIndex++;
            }

            if (matchedEnd > 0) {
                int origStart = compressedToOriginal[start];
                int origEnd = compressedToOriginal[matchedEnd - 1] + 1;
                if (!isInWhitelistRange(origStart, origEnd, text, whitelist)) {
                    for (int pos : matchedPositions) {
                        toReplace[pos] = true;
                    }
                    matched = true;
                }
            }
        }
        return matched;
    }

    public static boolean containsAnyBannedWord(String text, Map<String, List<String>> bannedWordsByLevel,
            boolean fuzzyMatch, int defaultMaxCharGap, Map<String, Integer> maxCharGapByLevel,
            boolean reverseMatch, Map<String, Boolean> reverseMatchByLevel, Iterable<String> whitelist) {
        if (text == null) {
            return false;
        }

        for (Map.Entry<String, List<String>> entry : bannedWordsByLevel.entrySet()) {
            String level = entry.getKey();
            int maxCharGap = maxCharGapByLevel.getOrDefault(level, defaultMaxCharGap);
            boolean levelReverseMatch = reverseMatch && (reverseMatchByLevel == null ||
                    reverseMatchByLevel.getOrDefault(level, true));

            for (String bannedWord : entry.getValue()) {
                if (bannedWord != null && !bannedWord.isEmpty()) {
                    if (containsBannedWord(text, bannedWord, fuzzyMatch, maxCharGap, whitelist)) {
                        return true;
                    }

                    if (levelReverseMatch && bannedWord.length() >= 2) {
                        String reversedWord = reverseWord(bannedWord);
                        if (containsBannedWord(text, reversedWord, fuzzyMatch, maxCharGap, whitelist)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
