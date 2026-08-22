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

        // ---- 1. 收集页面文本并合并（合并检测可捕获跨页违禁词）----
        List<String> pageTexts = new ArrayList<>();
        if (bookMeta.hasPages()) {
            for (Component page : bookMeta.pages()) {
                pageTexts.add(legacySerializer.serialize(page));
            }
        }

        int[] positions = new int[pageTexts.size()];
        String combinedText = combineLinesWithNewlines(pageTexts.toArray(new String[0]), positions);
        BannedWordDetection combinedDetection = filterTextWithRecheck(combinedText);
        boolean combinedFound = combinedText != null && !combinedText.equals(combinedDetection.getFilteredText());

        // ---- 2. 检测标题 ----
        BannedWordDetection titleDetection = null;
        String title = null;
        if (bookMeta.hasTitle()) {
            Component titleComponent = bookMeta.title();
            if (titleComponent != null) {
                title = legacySerializer.serialize(titleComponent);
                titleDetection = filterTextWithRecheck(title);
            }
        }

        // ---- 3. 检测作者 ----
        BannedWordDetection authorDetection = null;
        String author = null;
        if (bookMeta.hasAuthor()) {
            author = bookMeta.getAuthor();
            authorDetection = filterTextWithRecheck(author);
        }

        // ---- 4. 汇总检测结果 ----
        boolean titleFound = titleDetection != null && title != null && !title.equals(titleDetection.getFilteredText());
        boolean authorFound = authorDetection != null && author != null && !author.equals(authorDetection.getFilteredText());
        boolean foundBannedWord = combinedFound || titleFound || authorFound;

        if (!foundBannedWord) {
            return;
        }

        // 聚合所有检测到的违禁词（addDetectedWord 自带去重，用于管理提醒）
        List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
        if (combinedDetection != null) {
            allDetected.addAll(combinedDetection.getDetectedWords());
        }
        if (titleDetection != null) {
            allDetected.addAll(titleDetection.getDetectedWords());
        }
        if (authorDetection != null) {
            allDetected.addAll(authorDetection.getDetectedWords());
        }

        // 确定首个违禁词及等级（用于玩家警告）
        String bannedWord = "";
        String level = "";
        if (combinedFound && combinedDetection.hasDetectedWords()) {
            bannedWord = combinedDetection.getFirstBannedWord();
            level = combinedDetection.getFirstLevel();
        }
        if (bannedWord.isEmpty() && titleFound) {
            bannedWord = titleDetection.getFirstBannedWord();
            level = titleDetection.getFirstLevel();
        }
        if (bannedWord.isEmpty() && authorFound) {
            bannedWord = authorDetection.getFirstBannedWord();
            level = authorDetection.getFirstLevel();
        }

        plugin.getCrossMessageTracker().removePlayer(player.getUniqueId());
        plugin.sendWarnings(player, plugin.getConfigManager().getContextName("book"), bannedWord, level, allDetected);

        // ---- 5. 通过事件 API 应用过滤结果 ----
        // 直接修改 PlayerEditBookEvent 的新书本元数据，让服务端在事件完成后自然保存。
        // 不可在事件后通过延迟任务 setItem 回写玩家物品栏，否则会触发客户端
        // "编辑书本过快"检测并踢出玩家（不要以玩家身份去编辑书与笔）。
        if (combinedFound) {
            String filteredCombined = combinedDetection.getFilteredText();
            String[] filteredPages = splitTextToLines(filteredCombined, positions, pageTexts.size());
            List<Component> newPages = new ArrayList<>();
            for (int i = 0; i < filteredPages.length; i++) {
                String fp = filteredPages[i] != null ? filteredPages[i] : "";
                newPages.add(legacySerializer.deserialize(fp));
            }
            bookMeta.pages(newPages);
        }

        if (titleFound) {
            bookMeta.title(legacySerializer.deserialize(titleDetection.getFilteredText()));
        }

        if (authorFound) {
            bookMeta.setAuthor(authorDetection.getFilteredText());
        }

        event.setNewBookMeta(bookMeta);
    }

    /**
     * 对文本执行违禁词检测，并在首次替换后反复复核处理后的文本，直到结果稳定。
     * 例如 "傻傻逼逼" 经一次替换可能得到 "*傻*逼"，此时 "傻*逼" 仍可匹配违禁词，
     * 需再次检测直至无违禁词残留。
     * 由于 {@link TextProcessor#replaceInOriginalWithMask} 为 1:1 字符替换，
     * 文本长度保持不变，位置映射不受影响。
     */
    private BannedWordDetection filterTextWithRecheck(String text) {
        ConfigManager config = plugin.getConfigManager();
        return ColorCodeUtils.filterAllWithRecheck(text, config.getBannedWordsByLevel(),
                config.isFuzzyMatchEnable(), config.getDefaultMaxCharGap(),
                config.getMaxCharGapByLevel(), config.isReverseMatchEnable(),
                config.getReverseMatchByLevel(), config.getWhitelist());
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

            // 排除 combineLinesWithNewlines 在页面间添加的换行分隔符。
            // 该 \n 是分隔符而非页面原始内容，若保留会导致页面多出空行。
            // 仅在非末页且边界处确为 \n 时排除，避免误删页面内容自身的换行。
            if (i < maxLines - 1 && end > start && end <= text.length() && text.charAt(end - 1) == '\n') {
                end--;
            }

            lines[i] = text.substring(start, end);
        }
        
        return lines;
    }

    private BannedWordDetection filterTextWithDetection(String text) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        CharGapLimits defaultLimits = plugin.getConfigManager().getDefaultMaxCharGap();
        boolean reverseMatch = plugin.getConfigManager().isReverseMatchEnable();

        // 使用 filterAllWithRecheck：替换后继续复核，检出二次组合的违禁词（如 "傻他妈的逼" 中的 "他妈"）
        return ColorCodeUtils.filterAllWithRecheck(text, plugin.getConfigManager().getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, plugin.getConfigManager().getMaxCharGapByLevel(),
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