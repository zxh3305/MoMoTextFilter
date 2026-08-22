package com.momocraft.textfilter.mixin;

import com.momocraft.textfilter.*;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignBlockEntityMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void onHandleSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        MoMoTextFilterMod mod = MoMoTextFilterMod.getInstance();
        if (mod == null) return;

        boolean foundBannedWord = false;
        String bannedWord = "";
        String level = "";

        // 1. 逐行过滤（就地修改 packet 内部数组，不 cancel，实现替代原 Bukkit 的 setLine 行为）
        for (int i = 0; i < 4; i++) {
            String lineText = packet.getLines()[i];
            if (lineText == null || lineText.isEmpty()) {
                continue;
            }

            BannedWordDetection detection = filterTextWithDetection(lineText, mod.getConfigManager());

            if (!lineText.equals(detection.getFilteredText())) {
                packet.getLines()[i] = detection.getFilteredText();
                foundBannedWord = true;
                if (bannedWord.isEmpty()) {
                    bannedWord = detection.getFirstBannedWord();
                    level = detection.getFirstLevel();
                }
            }
        }

        // 2. 跨行合并检测（修复后自动作用于 packet 已修改的行）
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = packet.getLines()[i];
        }
        int[] positions = new int[4];
        String combinedText = combineLinesWithNewlines(lines, positions);
        BannedWordDetection combinedDetection = filterTextWithDetection(combinedText, mod.getConfigManager());
        boolean combinedFound = !combinedText.equals(combinedDetection.getFilteredText());

        if (combinedFound) {
            String filteredCombined = combinedDetection.getFilteredText();
            String[] filteredLines = splitTextToLines(filteredCombined, positions, 4);
            for (int i = 0; i < filteredLines.length && i < 4; i++) {
                if (filteredLines[i] != null) {
                    packet.getLines()[i] = filteredLines[i];
                }
            }
            foundBannedWord = true;
            if (bannedWord.isEmpty()) {
                bannedWord = combinedDetection.getFirstBannedWord();
                level = combinedDetection.getFirstLevel();
            }
        }

        if (foundBannedWord) {
            mod.getCrossMessageTracker().removePlayer(player.getUUID());
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            if (combinedDetection != null) {
                allDetected.addAll(combinedDetection.getDetectedWords());
            }
            mod.sendWarnings(player, mod.getConfigManager().getContextName("sign"), bannedWord, level, allDetected);
            // 不 cancel — getLines() 数组已就地修改，Vanilla 后续逻辑会从该数组读取过滤后的文字
        }
    }

    private BannedWordDetection filterTextWithDetection(String text, FabricConfigManager config) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits defaultLimits = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        return ColorCodeUtils.filterAllWithRecheck(text, config.getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
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