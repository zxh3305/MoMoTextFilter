package com.momocraft.textfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

public class TextFilterListener implements Listener {

    private final TextFilter plugin;
    private final LegacyComponentSerializer legacySerializer;

    public TextFilterListener(TextFilter plugin) {
        this.plugin = plugin;
        this.legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        boolean foundBannedWord = false;
        String bannedWord = "";
        String level = "";

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = event.getLine(i);
        }

        String combinedText = combineLinesWithNewlines(lines);
        BannedWordDetection combinedDetection = filterTextWithDetection(combinedText);
        boolean combinedFound = !combinedText.equals(combinedDetection.getFilteredText());

        for (int i = 0; i < 4; i++) {
            String lineText = lines[i];
            if (lineText == null || lineText.isEmpty()) {
                continue;
            }

            BannedWordDetection detection = filterTextWithDetection(lineText);

            if (!lineText.equals(detection.getFilteredText())) {
                event.setLine(i, detection.getFilteredText());
                foundBannedWord = true;
                if (bannedWord.isEmpty()) {
                    bannedWord = detection.getFirstBannedWord();
                    level = detection.getFirstLevel();
                }
            }
        }

        if (combinedFound) {
            String filteredCombined = combinedDetection.getFilteredText();
            String[] filteredLines = splitTextToLines(filteredCombined, 4);
            for (int i = 0; i < 4; i++) {
                if (i < filteredLines.length && filteredLines[i] != null) {
                    event.setLine(i, filteredLines[i]);
                }
            }
            foundBannedWord = true;
            if (bannedWord.isEmpty()) {
                bannedWord = combinedDetection.getFirstBannedWord();
                level = combinedDetection.getFirstLevel();
            }
        }

        if (foundBannedWord) {
            plugin.getCrossMessageTracker().removePlayer(player.getUniqueId());
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            if (combinedDetection != null) {
                allDetected.addAll(combinedDetection.getDetectedWords());
            }
            plugin.sendWarnings(player, plugin.getConfigManager().getContextName("sign"), bannedWord, level, allDetected);

            if (plugin.getConfig().getBoolean("sign-settings.delayed-replace.enable", true)) {
                Block block = event.getBlock();
                int delayTicks = plugin.getConfig().getInt("sign-settings.delayed-replace.delay-ticks", 1);

                SchedulerCompat.runTaskLater(plugin, block.getLocation(), () -> {
                    if (block.getState() instanceof Sign sign) {
                        boolean needUpdate = false;

                        for (Side side : Side.values()) {
                            SignSide signSide = sign.getSide(side);
                            boolean sideNeedUpdate = filterSignSide(signSide);
                            if (sideNeedUpdate) {
                                needUpdate = true;
                            }
                        }

                        if (needUpdate) {
                            sign.update();
                        }
                    }
                }, delayTicks);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        BookMeta bookMeta = event.getNewBookMeta();

        if (bookMeta == null) {
            return;
        }

        boolean foundBannedWord = false;
        String bannedWord = "";
        String level = "";

        List<String> pageTexts = new ArrayList<>();
        if (bookMeta.hasPages()) {
            for (Component page : bookMeta.pages()) {
                String pageText = legacySerializer.serialize(page);
                pageTexts.add(pageText);
            }
        }

        String combinedText = combineLinesWithNewlines(pageTexts.toArray(new String[0]));
        BannedWordDetection combinedDetection = filterTextWithDetection(combinedText);
        boolean combinedFound = !combinedText.equals(combinedDetection.getFilteredText());

        if (bookMeta.hasTitle()) {
            Component titleComponent = bookMeta.title();
            if (titleComponent != null) {
                String title = legacySerializer.serialize(titleComponent);
                BannedWordDetection detection = filterTextWithDetection(title);
                if (!title.equals(detection.getFilteredText())) {
                    foundBannedWord = true;
                    bannedWord = detection.getFirstBannedWord();
                    level = detection.getFirstLevel();
                }
            }
        }

        if (!foundBannedWord && bookMeta.hasAuthor()) {
            String author = bookMeta.getAuthor();
            BannedWordDetection detection = filterTextWithDetection(author);
            if (!author.equals(detection.getFilteredText())) {
                foundBannedWord = true;
                bannedWord = detection.getFirstBannedWord();
                level = detection.getFirstLevel();
            }
        }

        if (!foundBannedWord && bookMeta.hasPages()) {
            List<Component> pages = bookMeta.pages();
            for (Component page : pages) {
                String pageText = legacySerializer.serialize(page);
                BannedWordDetection detection = filterTextWithDetection(pageText);
                if (!pageText.equals(detection.getFilteredText())) {
                    foundBannedWord = true;
                    bannedWord = detection.getFirstBannedWord();
                    level = detection.getFirstLevel();
                    break;
                }
            }
        }

        if (combinedFound) {
            foundBannedWord = true;
            if (bannedWord.isEmpty()) {
                bannedWord = combinedDetection.getFirstBannedWord();
                level = combinedDetection.getFirstLevel();
            }
        }

        if (foundBannedWord) {
            plugin.getCrossMessageTracker().removePlayer(player.getUniqueId());
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            if (combinedDetection != null) {
                allDetected.addAll(combinedDetection.getDetectedWords());
            }
            plugin.sendWarnings(player, plugin.getConfigManager().getContextName("book"), bannedWord, level, allDetected);

            SchedulerCompat.runTaskLater(plugin, player, () -> {
                for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                    ItemStack item = player.getInventory().getItem(slot);
                    if (item != null && item.getType().name().contains("BOOK") && item.hasItemMeta()) {
                        BookMeta handBookMeta = (BookMeta) item.getItemMeta();
                        boolean needUpdate = false;
                        List<Component> newPages = new ArrayList<>();

                        if (handBookMeta.hasTitle()) {
                            Component titleComponent = handBookMeta.title();
                            if (titleComponent != null) {
                                String title = legacySerializer.serialize(titleComponent);
                                BannedWordDetection detection = filterTextWithDetection(title);
                                String filteredTitle = detection.getFilteredText();
                                if (!title.equals(filteredTitle)) {
                                    handBookMeta.title(legacySerializer.deserialize(filteredTitle));
                                    needUpdate = true;
                                }
                            }
                        }

                        if (handBookMeta.hasAuthor()) {
                            String author = handBookMeta.getAuthor();
                            BannedWordDetection detection = filterTextWithDetection(author);
                            String filteredAuthor = detection.getFilteredText();
                            if (!author.equals(filteredAuthor)) {
                                handBookMeta.setAuthor(filteredAuthor);
                                needUpdate = true;
                            }
                        }

                        if (handBookMeta.hasPages()) {
                            List<String> bookPageTexts = new ArrayList<>();
                            for (Component page : handBookMeta.pages()) {
                                bookPageTexts.add(legacySerializer.serialize(page));
                            }

                            String bookCombinedText = combineLinesWithNewlines(bookPageTexts.toArray(new String[0]));
                            BannedWordDetection bookCombinedDetection = filterTextWithDetection(bookCombinedText);

                            if (!bookCombinedText.equals(bookCombinedDetection.getFilteredText())) {
                                String filteredCombined = bookCombinedDetection.getFilteredText();
                                String[] filteredLines = splitTextToLines(filteredCombined, bookPageTexts.size());
                                for (int i = 0; i < filteredLines.length; i++) {
                                    String filteredPage = filteredLines[i] != null ? filteredLines[i] : "";
                                    newPages.add(legacySerializer.deserialize(filteredPage));
                                }
                                handBookMeta.pages(newPages);
                                needUpdate = true;
                            } else {
                                for (Component page : handBookMeta.pages()) {
                                    String pageText = legacySerializer.serialize(page);
                                    BannedWordDetection detection = filterTextWithDetection(pageText);
                                    String filteredPage = detection.getFilteredText();
                                    if (!pageText.equals(filteredPage)) {
                                        needUpdate = true;
                                    }
                                    newPages.add(legacySerializer.deserialize(filteredPage));
                                }
                                handBookMeta.pages(newPages);
                            }
                        }

                        if (needUpdate) {
                            item.setItemMeta(handBookMeta);
                            player.getInventory().setItem(slot, item);
                        }
                    }
                }
            }, 1);
        }
    }

    private String combineLinesWithNewlines(String[] lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            if (lines[i] != null) {
                sb.append(lines[i]);
            }
        }
        return sb.toString();
    }

    private String[] splitTextToLines(String text, int maxLines) {
        String[] lines = new String[maxLines];
        if (text == null || text.isEmpty()) {
            return lines;
        }

        String[] parts = text.split("\n", maxLines);
        for (int i = 0; i < Math.min(parts.length, maxLines); i++) {
            lines[i] = parts[i];
        }
        return lines;
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

    private boolean filterSignSide(SignSide signSide) {
        boolean needUpdate = false;

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = signSide.getLine(i);
        }

        String combinedText = combineLinesWithNewlines(lines);
        BannedWordDetection combinedDetection = filterTextWithDetection(combinedText);
        boolean combinedFound = !combinedText.equals(combinedDetection.getFilteredText());

        for (int i = 0; i < 4; i++) {
            String lineText = lines[i];
            if (lineText == null || lineText.isEmpty()) {
                continue;
            }

            BannedWordDetection detection = filterTextWithDetection(lineText);

            if (!lineText.equals(detection.getFilteredText())) {
                signSide.setLine(i, detection.getFilteredText());
                needUpdate = true;
            }
        }

        if (combinedFound) {
            String filteredCombined = combinedDetection.getFilteredText();
            String[] filteredLines = splitTextToLines(filteredCombined, 4);
            for (int i = 0; i < 4; i++) {
                if (i < filteredLines.length && filteredLines[i] != null) {
                    signSide.setLine(i, filteredLines[i]);
                }
            }
            needUpdate = true;
        }

        return needUpdate;
    }
}