package com.momocraft.textfilter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class ExternalPluginListener implements Listener {

    private final TextFilter plugin;

    public ExternalPluginListener(TextFilter plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (message == null || message.isEmpty()) {
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        CommandType cmdType = config.findMatchingCommandType(message);

        if (cmdType == null) {
            return;
        }

        String contextName = config.getContextName(cmdType.getName());
        String extractedMessage = cmdType.extractMessage(message);

        CrossMessageTracker.TrackingResult trackingResult = plugin.getCrossMessageTracker().checkAndTrack(player, extractedMessage, contextName);
        if (trackingResult != null) {
            String filteredMessage = extractedMessage;
            if (trackingResult.isCrossMessageMatch()) {
                int[] positions = trackingResult.getMatchedPositionsInCurrent();
                if (positions != null && positions.length > 0) {
                    filteredMessage = replaceCrossByPositions(extractedMessage, positions);
                } else {
                    filteredMessage = replaceCrossMessageBannedWord(extractedMessage, trackingResult.getBannedWord(), config.isFuzzyMatchEnable(),
                            config.getDefaultMaxCharGap());
                }
            }
            if (trackingResult.isCrossMessageMatch()) {
                if (!extractedMessage.equals(filteredMessage)) {
                    String newCommand = cmdType.replaceMessage(message, filteredMessage);
                    if (!message.equals(newCommand)) {
                        event.setMessage(newCommand);
                    }
                }
            } else {
                BannedWordDetection detection = filterTextWithDetection(extractedMessage, config);
                if (!extractedMessage.equals(detection.getFilteredText())) {
                    String newCommand = cmdType.replaceMessage(message, detection.getFilteredText());
                    if (!message.equals(newCommand)) {
                        event.setMessage(newCommand);
                    }
                }
            }
            plugin.sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel());
            return;
        }

        BannedWordDetection detection = filterTextWithDetection(extractedMessage, config);
        if (!extractedMessage.equals(detection.getFilteredText())) {
            String newCommand = cmdType.replaceMessage(message, detection.getFilteredText());
            if (!message.equals(newCommand)) {
                event.setMessage(newCommand);
            }
            plugin.sendWarnings(player, contextName, detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
        }
    }

    private BannedWordDetection filterTextWithDetection(String text, ConfigManager config) {
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

    /** 命令文本的跨消息替换（无 MiniMessage 标签，使用 strip+索引直接打码）。
     *  支持 fuzzy 夹字匹配：从右端向左扫描 bannedWord 后缀，中间非命中字符计 gap，任一类超限即停止。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord, boolean fuzzyMatch, CharGapLimits limits) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return currentMessage;
        }
        String lowerCurrent = CharacterMapper.normalize(ColorCodeUtils.stripAllFormatting(currentMessage).toLowerCase());
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
        if (lowerCurrent.isEmpty() || lowerBanned.isEmpty() || lowerCurrent.length() != currentMessage.length()) {
            // stripAllFormatting 改了长度时（含颜色代码场景），退化为 1:1 扫描
            lowerCurrent = currentMessage.toLowerCase();
        }
        boolean[] toReplace = new boolean[currentMessage.length()];
        int bannedIdx = lowerBanned.length() - 1;
        boolean foundAny = false;
        int chineseGap = 0, englishGap = 0, othersGap = 0;

        for (int i = lowerCurrent.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (lowerCurrent.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                if (!foundAny) continue; // 没开始匹配前允许跳过末端的无关字符
                if (fuzzyMatch) {
                    switch (CharacterMapper.classify(lowerCurrent.charAt(i))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        if (!foundAny) return currentMessage;
        StringBuilder sb = new StringBuilder(currentMessage.length());
        for (int i = 0; i < currentMessage.length(); i++) {
            sb.append(toReplace[i] ? "*" : currentMessage.charAt(i));
        }
        return sb.toString();
    }

    /** 按索引直接打码（命令文本无 MiniMessage 标签，1:1 映射）。 */
    private String replaceCrossByPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        boolean[] toReplace = new boolean[currentMessage.length()];
        for (int p : positions) {
            if (p >= 0 && p < currentMessage.length()) toReplace[p] = true;
        }
        StringBuilder sb = new StringBuilder(currentMessage.length());
        for (int i = 0; i < currentMessage.length(); i++) {
            sb.append(toReplace[i] ? "*" : currentMessage.charAt(i));
        }
        return sb.toString();
    }
}
