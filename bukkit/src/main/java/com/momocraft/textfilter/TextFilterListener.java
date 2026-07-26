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

        int[] positions = new int[4];
        String combinedText = combineLinesWithNewlines(lines, positions);
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
            String[] filteredLines = splitTextToLines(filteredCombined, positions, 4);
            for (int i = 0; i < filteredLines.length && i < 4; i++) {
                if (filteredLines[i] != null) {
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

        int[] positions = new int[pageTexts.size()];
        String combinedText = combineLinesWithNewlines(pageTexts.toArray(new String[0]), positions);
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

                            int[] bookPositions = new int[bookPageTexts.size()];
                            String bookCombinedText = combineLinesWithNewlines(bookPageTexts.toArray(new String[0]), bookPositions);
                            BannedWordDetection bookCombinedDetection = filterTextWithDetection(bookCombinedText);

                            if (!bookCombinedText.equals(bookCombinedDetection.getFilteredText())) {
                                String filteredCombined = bookCombinedDetection.getFilteredText();
                                String[] filteredLines = splitTextToLines(filteredCombined, bookPositions, bookPageTexts.size());
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

    /**
     * 将多行文本合并为一个字符串，页面之间用换行符分隔。
     * 同时记录每个页面在合并文本中的起始字符位置。
     * @param lines 原始页面文本数组
     * @param positions 输出参数：各页在合并文本中的起始字符位置（需提前分配空间）
     * @return 合并后的文本
     */
    private String combineLinesWithNewlines(String[] lines, int[] positions) {
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < lines.length; i++) {
            // 记录当前页面的起始位置
            if (i < positions.length) {
                positions[i] = sb.length();
            }
            
            if (lines[i] != null) {
                sb.append(lines[i]);
            }
            
            // 页面之间用换行符分隔，最后一页不加
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }

    /**
     * 将过滤后的文本按原始页面边界分割。
     * 由于 TextProcessor.replaceInOriginalWithMask() 做的是 1:1 字符替换，
     * 过滤后的文本长度与原始文本完全相同，因此可以直接使用原始位置进行精确分割。
     * @param text 过滤后的文本
     * @param originalPositions 原始各页在合并文本中的起始字符位置
     * @param maxLines 原始页数
     */
    private String[] splitTextToLines(String text, int[] originalPositions, int maxLines) {
        String[] lines = new String[maxLines];
        if (text == null || text.isEmpty()) {
            for (int i = 0; i < maxLines; i++) {
                lines[i] = "";
            }
            return lines;
        }

        for (int i = 0; i < maxLines; i++) {
            // 获取该页面的起始位置
            int start = (i < originalPositions.length) ? originalPositions[i] : text.length();
            
            // 获取该页面的结束位置（下一页的起始位置或文本末尾）
            int end;
            if (i < maxLines - 1 && i + 1 < originalPositions.length) {
                end = originalPositions[i + 1];
            } else {
                end = text.length();
            }
            
            // 确保边界有效
            start = Math.max(0, Math.min(start, text.length()));
            end = Math.max(start, Math.min(end, text.length()));
            
            lines[i] = text.substring(start, end);
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

        int[] positions = new int[4];
        String combinedText = combineLinesWithNewlines(lines, positions);
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
            String[] filteredLines = splitTextToLines(filteredCombined, positions, 4);
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
