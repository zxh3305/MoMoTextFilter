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
            if (trackingResult.isCrossMessageMatch()) {
                String filteredMessage = replaceCrossMessageBannedWord(extractedMessage, trackingResult.getBannedWord());
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
        int defaultMaxCharGap = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        return ColorCodeUtils.filterAllBannedWordsWithDetection(text, config.getBannedWordsByLevel(),
                fuzzyMatch, defaultMaxCharGap, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
    }

    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return currentMessage;
        }

        String lowerCurrent = ColorCodeUtils.stripAllFormatting(currentMessage).toLowerCase();
        String lowerBanned = bannedWord.toLowerCase();

        for (int i = 1; i <= lowerCurrent.length(); i++) {
            String suffix = lowerCurrent.substring(lowerCurrent.length() - i);
            if (lowerBanned.endsWith(suffix)) {
                String replacement = "*".repeat(i);
                return currentMessage.substring(0, currentMessage.length() - i) + replacement;
            }
        }

        return currentMessage;
    }
}