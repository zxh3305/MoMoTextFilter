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

        // 0. 第一轮：整体剔除 MiniMessage 标签块检测（含复核循环，直至整剔文本无法再检出）
        //    例：傻<click:run_command:/say 一二三>逼</click> 整剔后为 "傻逼" → 命中打码 → *<click:...>*</click>
        BannedWordDetection strippedDetection = filterStrippedTagsWithDetection(message);
        String baseText = strippedDetection.hasDetectedWords() ? strippedDetection.getFilteredText() : message;

        // 1. 常规检测（连续字符 > 跨字符）在复原标签结构后的文本上继续：先完成检测与替换
        BannedWordDetection detection = filterTextWithDetection(baseText);
        String filteredMessage = detection.getFilteredText();
        boolean singleHit = !baseText.equals(filteredMessage);

        // 2. 单消息处理完毕后再做跨消息检测：
        //    以替换后的文本为准（被打码的字符视为不存在），因此已被打码的字符不会再参与跨消息匹配
        CrossMessageTracker.TrackingResult trackingResult = plugin.getCrossMessageTracker().checkAndTrack(player, filteredMessage, contextName);
        BannedWordDetection recheckDetection = null;
        if (trackingResult != null && trackingResult.isCrossMessageMatch()) {
            int[] positions = trackingResult.getMatchedPositionsInCurrent();
            if (positions != null && positions.length > 0) {
                filteredMessage = trackingResult.isPositionsInStrippedView()
                        ? replaceCrossByPositions(filteredMessage, positions)
                        : replaceCrossByProcessedPositions(filteredMessage, positions);
            } else {
                filteredMessage = replaceCrossMessageBannedWord(filteredMessage, trackingResult.getBannedWord());
            }

            // 跨消息替换后复核，防止打码后剩余字符组合出新的违禁词
            recheckDetection = filterTextWithDetection(filteredMessage);
            if (!filteredMessage.equals(recheckDetection.getFilteredText())) {
                filteredMessage = recheckDetection.getFilteredText();
            }
        }

        if (!message.equals(filteredMessage)) {
            event.setMessage(filteredMessage);
        }

        // 3. 合并去重警告：跨消息命中为主，整剔/单消息/复核命中并入
        if (trackingResult != null) {
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            String trackingKey = trackingResult.getBannedWord() + ":" + trackingResult.getLevel();
            Set<String> addedKeys = new HashSet<>();
            addedKeys.add(trackingKey);
            allDetected.add(new BannedWordDetection.BannedWordInfo(trackingResult.getBannedWord(), trackingResult.getLevel()));
            mergeDetected(allDetected, addedKeys, strippedDetection.getDetectedWords());
            mergeDetected(allDetected, addedKeys, singleHit ? detection.getDetectedWords() : null);
            mergeDetected(allDetected, addedKeys, recheckDetection == null ? null : recheckDetection.getDetectedWords());
            plugin.sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel(), allDetected);
        } else if (singleHit || strippedDetection.hasDetectedWords()) {
            BannedWordDetection primary = strippedDetection.hasDetectedWords() ? strippedDetection : detection;
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            Set<String> addedKeys = new HashSet<>();
            mergeDetected(allDetected, addedKeys, primary.getDetectedWords());
            mergeDetected(allDetected, addedKeys, primary == strippedDetection ? detection.getDetectedWords() : strippedDetection.getDetectedWords());
            plugin.sendWarnings(player, contextName, primary.getFirstBannedWord(), primary.getFirstLevel(), allDetected);
        }
    }

    /** 将检测结果并入警告列表（按 词:等级 去重）。 */
    private void mergeDetected(List<BannedWordDetection.BannedWordInfo> allDetected, Set<String> addedKeys,
            List<BannedWordDetection.BannedWordInfo> detectedWords) {
        if (detectedWords == null) return;
        for (BannedWordDetection.BannedWordInfo info : detectedWords) {
            String key = info.getWord() + ":" + info.getLevel();
            if (!addedKeys.contains(key)) {
                addedKeys.add(key);
                allDetected.add(info);
            }
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

    /** 第一轮：整体剔除 MiniMessage 标签块后的检测（例：傻<click:run_command:/say 一>逼</click> 整剔为 "傻逼"）。 */
    private BannedWordDetection filterStrippedTagsWithDetection(String text) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        CharGapLimits defaultLimits = plugin.getConfigManager().getDefaultMaxCharGap();
        boolean reverseMatch = plugin.getConfigManager().isReverseMatchEnable();

        return ColorCodeUtils.filterStrippedTagsWithDetection(text, plugin.getConfigManager().getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, plugin.getConfigManager().getMaxCharGapByLevel(),
                reverseMatch, plugin.getConfigManager().getReverseMatchByLevel(), plugin.getConfigManager().getWhitelist());
    }

    /** 跨消息 suffix 打码（strict 连续匹配）：在指定视图（整剔/载荷）末尾向左扫描 bannedWord 后缀，
     *  遇不匹配字符立即停止 —— 只替换"连续后缀匹配段"（如 "逼·" 只打码 "逼"，保留 "·"）。
     *  返回 null 表示该视图未命中。 */
    private String replaceCrossSuffixInView(String currentMessage, String bannedWord, boolean strippedView) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return null;
        }

        TextProcessor processor = new TextProcessor(currentMessage);
        String view = strippedView ? processor.getStrippedTagsText() : processor.getProcessedText();
        String viewText = CharacterMapper.normalize((view == null ? "" : view).toLowerCase());
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
        if (viewText.isEmpty() || lowerBanned.isEmpty()) {
            return null;
        }

        boolean[] toReplace = new boolean[viewText.length()];
        int bannedIdx = lowerBanned.length() - 1;
        boolean foundAny = false;

        for (int i = viewText.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (viewText.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                // 遇到不匹配字符立即停止；保证只替换"连续后缀匹配段"
                if (foundAny) break;
            }
        }

        if (!foundAny) {
            return null;
        }
        return strippedView ? processor.replaceInOriginalWithStrippedMask(toReplace, "*")
                            : processor.replaceInOriginalWithMask(toReplace, "*");
    }

    /** fallback：整剔域优先扫 bannedWord 后缀，未命中退回载荷域（复原标签后的常规形态）。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        String r = replaceCrossSuffixInView(currentMessage, bannedWord, true);
        if (r != null) return r;
        return replaceCrossSuffixInView(currentMessage, bannedWord, false);
    }

    /** 优先走这条：CrossMessageTracker 已计算出当前消息内应打码的整剔域索引，直接按位置打码。 */
    private String replaceCrossByPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        TextProcessor processor = new TextProcessor(currentMessage);
        String strippedView = processor.getStrippedTagsText();
        int len = strippedView == null ? 0 : strippedView.length();
        if (len == 0) return currentMessage;
        boolean[] toReplace = new boolean[len];
        for (int p : positions) {
            if (p >= 0 && p < len) toReplace[p] = true;
        }
        return processor.replaceInOriginalWithStrippedMask(toReplace, "*");
    }

    /** tracker 命中于载荷域（processedText）时：按载荷视图索引定点打码。 */
    private String replaceCrossByProcessedPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        TextProcessor processor = new TextProcessor(currentMessage);
        String view = processor.getProcessedText();
        int len = view == null ? 0 : view.length();
        if (len == 0) return currentMessage;
        boolean[] toReplace = new boolean[len];
        for (int p : positions) {
            if (p >= 0 && p < len) toReplace[p] = true;
        }
        return processor.replaceInOriginalWithMask(toReplace, "*");
    }
}
