package com.momocraft.textfilter.mixin;

import com.momocraft.textfilter.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BookEditMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void onHandleEditBook(ServerboundEditBookPacket packet, CallbackInfo ci) {
        MoMoTextFilterMod mod = MoMoTextFilterMod.getInstance();
        if (mod == null) return;

        FabricConfigManager config = mod.getConfigManager();

        // Collect page texts
        List<String> pageTexts = packet.pages();
        if (pageTexts == null || pageTexts.isEmpty()) {
            return;
        }

        int[] positions = new int[pageTexts.size()];
        String combinedText = combineLinesWithNewlines(pageTexts.toArray(new String[0]), positions);
        BannedWordDetection combinedDetection = filterTextWithRecheck(combinedText, config);
        boolean combinedFound = combinedText != null && !combinedText.equals(combinedDetection.getFilteredText());

        // Check title
        BannedWordDetection titleDetection = null;
        String title = packet.title().orElse(null);
        boolean titleFound = false;
        if (title != null && !title.isEmpty()) {
            titleDetection = filterTextWithRecheck(title, config);
            titleFound = !title.equals(titleDetection.getFilteredText());
        }

        if (!combinedFound && !titleFound) {
            return;
        }

        List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
        if (combinedDetection != null) {
            allDetected.addAll(combinedDetection.getDetectedWords());
        }
        if (titleDetection != null) {
            allDetected.addAll(titleDetection.getDetectedWords());
        }

        String bannedWord = "";
        String level = "";
        if (combinedFound && combinedDetection.hasDetectedWords()) {
            bannedWord = combinedDetection.getFirstBannedWord();
            level = combinedDetection.getFirstLevel();
        }
        if (bannedWord.isEmpty() && titleDetection != null && titleDetection.hasDetectedWords()) {
            bannedWord = titleDetection.getFirstBannedWord();
            level = titleDetection.getFirstLevel();
        }

        mod.getCrossMessageTracker().removePlayer(player.getUUID());
        mod.sendWarnings(player, config.getContextName("book"), bannedWord, level, allDetected);

        // Cancel the packet (prevent vanilla from saving the original content)
        ci.cancel();

        // Rewrite the player's book item with filtered content
        int slot = packet.slot();
        ItemStack bookItem = player.getInventory().getItem(slot);
        if (bookItem.isEmpty()) return;

        // Build filtered pages list
        List<String> filteredPages = new ArrayList<>();
        if (combinedFound) {
            String filteredCombined = combinedDetection.getFilteredText();
            String[] splitPages = splitTextToLines(filteredCombined, positions, pageTexts.size());
            for (String sp : splitPages) {
                filteredPages.add(sp != null ? sp : "");
            }
        } else {
            filteredPages.addAll(pageTexts);
        }

        // Apply filtered changes to the item
        boolean isSigning = title != null && !title.isEmpty();
        ItemStack newBook;

        if (isSigning) {
            // 签名流程：将可写书转换为成书
            // 使用 transmuteCopy 改变物品类型，与 vanilla signBook 一致
            newBook = bookItem.transmuteCopy(Items.WRITTEN_BOOK);
            newBook.remove(DataComponents.WRITABLE_BOOK_CONTENT);

            String filteredTitleText = titleFound ? titleDetection.getFilteredText() : title;
            List<Filterable<Component>> pageFilterables = new ArrayList<>();
            for (String page : filteredPages) {
                pageFilterables.add(Filterable.passThrough(Component.literal(page)));
            }
            newBook.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(
                    Filterable.passThrough(filteredTitleText),
                    player.getName().getString(),  // author = 玩家名
                    0,                              // generation = 0（原始副本）
                    pageFilterables,
                    false));                        // resolved = false（客户端会解析）
        } else {
            // 普通编辑：保持可写书
            newBook = bookItem.copy();
            // 更新可写书内容
            WritableBookContent writable = newBook.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writable != null) {
                List<Filterable<String>> pageFilterables = new ArrayList<>();
                for (String page : filteredPages) {
                    pageFilterables.add(Filterable.passThrough(page));
                }
                newBook.set(DataComponents.WRITABLE_BOOK_CONTENT,
                    new WritableBookContent(pageFilterables));
            }
            // 兜底：如果物品已经是成书（极少数情况，如用指令修改），也更新成书内容
            WrittenBookContent written = newBook.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (written != null) {
                List<Filterable<Component>> compPages = new ArrayList<>();
                for (String page : filteredPages) {
                    compPages.add(Filterable.passThrough(Component.literal(page)));
                }
                Filterable<String> filteredTitle = titleFound
                    ? Filterable.passThrough(titleDetection.getFilteredText())
                    : written.title();
                newBook.set(DataComponents.WRITTEN_BOOK_CONTENT,
                    new WrittenBookContent(filteredTitle, written.author(), written.generation(), compPages, written.resolved()));
            }
        }

        // Write back to inventory
        player.getInventory().setItem(slot, newBook);
    }

    private BannedWordDetection filterTextWithRecheck(String text, FabricConfigManager config) {
        if (text == null) return new BannedWordDetection(null);
        return ColorCodeUtils.filterAllWithRecheck(text, config.getBannedWordsByLevel(),
                config.isFuzzyMatchEnable(), config.getDefaultMaxCharGap(),
                config.getMaxCharGapByLevel(), config.isReverseMatchEnable(),
                config.getReverseMatchByLevel(), config.getWhitelist());
    }

    private String combineLinesWithNewlines(String[] lines, int[] positions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i < positions.length) {
                positions[i] = sb.length();
            }
            if (lines[i] != null) {
                sb.append(lines[i]);
            }
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String[] splitTextToLines(String text, int[] originalPositions, int maxLines) {
        String[] lines = new String[maxLines];
        if (text == null || text.isEmpty()) {
            for (int i = 0; i < maxLines; i++) {
                lines[i] = "";
            }
            return lines;
        }
        for (int i = 0; i < maxLines; i++) {
            int start = (i < originalPositions.length) ? originalPositions[i] : text.length();
            int end;
            if (i < maxLines - 1 && i + 1 < originalPositions.length) {
                end = originalPositions[i + 1];
            } else {
                end = text.length();
            }
            start = Math.max(0, Math.min(start, text.length()));
            end = Math.max(start, Math.min(end, text.length()));
            if (i < maxLines - 1 && end > start && end <= text.length() && text.charAt(end - 1) == '\n') {
                end--;
            }
            lines[i] = text.substring(start, end);
        }
        return lines;
    }
}