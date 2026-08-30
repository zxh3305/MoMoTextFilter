package com.momocraft.textfilter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatFilterListener implements Listener {

    private final TextFilter plugin;

    public ChatFilterListener(TextFilter plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (message == null || message.isEmpty()) {
            return;
        }

        String contextName = plugin.getConfigManager().getContextName("chat");

        CrossMessageTracker.TrackingResult trackingResult = plugin.getCrossMessageTracker().checkAndTrack(player, message, contextName);
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
            
            BannedWordDetection detection = filterTextWithDetection(filteredMessage);
            if (!filteredMessage.equals(detection.getFilteredText())) {
                filteredMessage = detection.getFilteredText();
            }
            
            if (!message.equals(filteredMessage)) {
                event.setMessage(filteredMessage);
            }
            
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            String trackingKey = trackingResult.getBannedWord() + ":" + trackingResult.getLevel();
            Set<String> addedKeys = new HashSet<>();
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
            return;
        }

        BannedWordDetection detection = filterTextWithDetection(message);

        if (!message.equals(detection.getFilteredText())) {
            event.setMessage(detection.getFilteredText());
            plugin.sendWarnings(player, contextName, detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
        }
    }

    private BannedWordDetection filterTextWithDetection(String text) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        CharGapLimits defaultLimits = plugin.getConfigManager().getDefaultMaxCharGap();
        boolean reverseMatch = plugin.getConfigManager().isReverseMatchEnable();

        // 使用 filterAllWithRecheck：替换后继续复核，满足 "傻他妈的逼" -> "*他妈的*" 后再检出 "他妈"
        return ColorCodeUtils.filterAllWithRecheck(text, plugin.getConfigManager().getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, plugin.getConfigManager().getMaxCharGapByLevel(),
                reverseMatch, plugin.getConfigManager().getReverseMatchByLevel(), plugin.getConfigManager().getWhitelist());
    }

    /** 跨消息匹配成功后，替换当前消息中匹配到的违禁词后缀字符。
     *  从 processedText 末尾向左查找"与 bannedWord 后缀能连续匹配"的字符段，
     *  遇不匹配字符立即停止 —— 这样 "逼·" 会只把 "逼" 标记为替换，保留 "·"，
     *  不再依赖严格的文本纯后缀匹配，避免匹配到了但替换不生效的 bug。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
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
                // 遇到不匹配字符立即停止；保证只替换"连续后缀匹配段"
                if (foundAny) break;
            }
        }

        if (foundAny) {
            return processor.replaceInOriginalWithMask(toReplace, "*");
        }
        return currentMessage;
    }

    /** 优先走这条：CrossMessageTracker 已计算出当前消息内应打码的 processedText 索引，直接按位置打码，
     *  结果比 listener 侧 fallback 的"向左扫 suffix"更精准（尤其夹字 fuzzy 命中场景）。 */
    private String replaceCrossByPositions(String currentMessage, int[] positions) {
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
