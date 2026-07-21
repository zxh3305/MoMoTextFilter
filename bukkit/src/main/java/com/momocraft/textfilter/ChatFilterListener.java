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
                filteredMessage = replaceCrossMessageBannedWord(message, trackingResult.getBannedWord());
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
        int defaultMaxCharGap = plugin.getConfigManager().getDefaultMaxCharGap();
        boolean reverseMatch = plugin.getConfigManager().isReverseMatchEnable();

        return ColorCodeUtils.filterAllBannedWordsWithDetection(text, plugin.getConfigManager().getBannedWordsByLevel(),
                fuzzyMatch, defaultMaxCharGap, plugin.getConfigManager().getMaxCharGapByLevel(),
                reverseMatch, plugin.getConfigManager().getReverseMatchByLevel(), plugin.getConfigManager().getWhitelist());
    }

    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return currentMessage;
        }

        TextProcessor processor = new TextProcessor(currentMessage);
        String processedText = CharacterMapper.normalize(processor.getProcessedText().toLowerCase());
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());

        for (int i = 1; i <= processedText.length(); i++) {
            String suffix = processedText.substring(processedText.length() - i);
            if (lowerBanned.endsWith(suffix)) {
                boolean[] toReplace = new boolean[processedText.length()];
                for (int j = processedText.length() - i; j < processedText.length(); j++) {
                    toReplace[j] = true;
                }
                return processor.replaceInOriginalWithMask(toReplace, "*");
            }
        }

        return currentMessage;
    }
}