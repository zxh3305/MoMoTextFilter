package com.momocraft.textfilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TextFilterApi {

    private static TextFilterVelocity plugin;
    private static final Map<UUID, List<String>> playerDetectedWords = new HashMap<>();

    static void init(TextFilterVelocity plugin) {
        TextFilterApi.plugin = plugin;
    }

    public static String filterServerShoutMessage(UUID playerUuid, String playerName, String message, String context) {
        if (plugin == null || message == null || message.isEmpty()) {
            return message;
        }

        ConfigManagerVelocity config = plugin.configManager;
        String contextName = config.getContextName(context != null ? context : "servershout");

        com.velocitypowered.api.proxy.Player player = plugin.proxy.getPlayer(playerUuid).orElse(null);
        if (player == null) {
            return filterSingleMessage(message, config);
        }

        CrossMessageTracker.TrackingResult trackingResult = plugin.crossMessageTracker.checkAndTrack(player, message, contextName);
        if (trackingResult != null) {
            String filteredMessage = message;

            if (trackingResult.isCrossMessageMatch()) {
                int[] positions = trackingResult.getMatchedPositionsInCurrent();
                if (positions != null && positions.length > 0) {
                    filteredMessage = replaceCrossByPositions(message, positions);
                } else {
                    filteredMessage = replaceCrossMessageBannedWord(message, trackingResult.getBannedWord());
                }
            }

            BannedWordDetection detection = filterTextWithDetection(filteredMessage, config);
            if (!filteredMessage.equals(detection.getFilteredText())) {
                filteredMessage = detection.getFilteredText();
            }

            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            String trackingKey = trackingResult.getBannedWord() + ":" + trackingResult.getLevel();
            java.util.Set<String> addedKeys = new java.util.HashSet<>();
            addedKeys.add(trackingKey);
            allDetected.add(new BannedWordDetection.BannedWordInfo(trackingResult.getBannedWord(), trackingResult.getLevel()));

            if (detection != null && detection.hasDetectedWords()) {
                for (BannedWordDetection.BannedWordInfo info : detection.getDetectedWords()) {
                    String key = info.getWord() + ":" + info.getLevel();
                    if (!addedKeys.contains(key)) {
                        addedKeys.add(key);
                        allDetected.add(info);
                    }
                }
            }

            plugin.sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel(), allDetected);
            return filteredMessage;
        }

        BannedWordDetection detection = filterTextWithDetection(message, config);
        if (!message.equals(detection.getFilteredText())) {
            plugin.sendWarnings(player, contextName, detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
            return detection.getFilteredText();
        }

        return message;
    }

    private static String filterSingleMessage(String message, ConfigManagerVelocity config) {
        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits defaultLimits = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        BannedWordDetection detection = ColorCodeUtils.filterAllWithRecheck(message,
                config.getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
        return detection.getFilteredText();
    }

    private static BannedWordDetection filterTextWithDetection(String text, ConfigManagerVelocity config) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits defaultLimits = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        // 使用 filterAllWithRecheck：替换后继续复核，检出二次组合的违禁词（如 "傻他妈的逼" 中的 "他妈"）
        return ColorCodeUtils.filterAllWithRecheck(text, config.getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
    }

    /** 跨消息匹配成功后，替换当前消息中匹配到的违禁词后缀字符。
     *  从 processedText 末尾向左查找"与 bannedWord 后缀能连续匹配"的字符段，
     *  遇不匹配字符立即停止 —— 这样 "逼·" 会只把 "逼" 标记为替换，保留 "·"。 */
    private static String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return currentMessage;
        }

        TextProcessor processor = new TextProcessor(currentMessage);
        String processedText = CharacterMapper.normalize(processor.getProcessedText().toLowerCase());
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
        if (processedText.isEmpty() || lowerBanned.isEmpty()) {
            return currentMessage;
        }

        boolean[] toReplace = new boolean[processedText.length()];
        int bannedIdx = lowerBanned.length() - 1;
        boolean foundAny = false;

        for (int i = processedText.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (processedText.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                if (foundAny) break;
            }
        }

        if (foundAny) {
            return processor.replaceInOriginalWithMask(toReplace, "*");
        }
        return currentMessage;
    }

    /** Velocity API 侧优先按 tracker 给出的 processedText 索引定点打码。 */
    private static String replaceCrossByPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        TextProcessor processor = new TextProcessor(currentMessage);
        int len = processor.getProcessedText() == null ? 0 : processor.getProcessedText().length();
        if (len == 0) return currentMessage;
        boolean[] toReplace = new boolean[len];
        for (int p : positions) {
            if (p >= 0 && p < len) toReplace[p] = true;
        }
        return processor.replaceInOriginalWithMask(toReplace, "*");
    }
}
