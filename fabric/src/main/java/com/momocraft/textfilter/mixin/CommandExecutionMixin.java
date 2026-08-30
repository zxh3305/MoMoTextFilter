package com.momocraft.textfilter.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.momocraft.textfilter.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandExecutionMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MoMoTextFilter");

    @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void onPerformCommand(ParseResults<CommandSourceStack> parseResults, String command,
                                   CallbackInfo ci) {
        if (command == null || command.isEmpty()) {
            return;
        }

        MoMoTextFilterMod mod = MoMoTextFilterMod.getInstance();
        if (mod == null) return;

        CommandSourceStack source = parseResults.getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return; // Only filter player commands
        }

        FabricConfigManager config = mod.getConfigManager();
        CommandType cmdType = config.findMatchingCommandType(command);

        if (cmdType == null) {
            return;
        }

        String contextName = config.getContextName(cmdType.getName());
        String extractedMessage = cmdType.extractMessage(command);

        CrossMessageTracker.TrackingResult trackingResult = mod.getCrossMessageTracker().checkAndTrack(player, extractedMessage, contextName);
        if (trackingResult != null) {
            if (trackingResult.isCrossMessageMatch()) {
                int[] positions = trackingResult.getMatchedPositionsInCurrent();
                String filteredMessage;
                if (positions != null && positions.length > 0) {
                    filteredMessage = replaceCrossByPositions(extractedMessage, positions);
                } else {
                    filteredMessage = replaceCrossMessageBannedWord(extractedMessage, trackingResult.getBannedWord());
                }
                if (!extractedMessage.equals(filteredMessage)) {
                    String newCommand = cmdType.replaceMessage(command, filteredMessage);
                    if (!command.equals(newCommand)) {
                        executeFiltered(newCommand, source, ci);
                    }
                }
            } else {
                BannedWordDetection detection = filterTextWithDetection(extractedMessage, config);
                if (!extractedMessage.equals(detection.getFilteredText())) {
                    String newCommand = cmdType.replaceMessage(command, detection.getFilteredText());
                    if (!command.equals(newCommand)) {
                        executeFiltered(newCommand, source, ci);
                    }
                }
            }
            mod.sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel());
            return;
        }

        BannedWordDetection detection = filterTextWithDetection(extractedMessage, config);
        if (!extractedMessage.equals(detection.getFilteredText())) {
            String newCommand = cmdType.replaceMessage(command, detection.getFilteredText());
            if (!command.equals(newCommand)) {
                executeFiltered(newCommand, source, ci);
            }
            mod.sendWarnings(player, contextName, detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
        }
    }

    private void executeFiltered(String command, CommandSourceStack source, CallbackInfo ci) {
        try {
            ci.cancel();
            dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            LOGGER.warn("Failed to execute filtered command: {}", command, e);
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

    /** 跨消息匹配成功后，替换当前消息中匹配到的违禁词后缀字符。
     *  从文本末尾向左查找"与 bannedWord 后缀能连续匹配"的字符段，
     *  遇不匹配字符立即停止 —— 这样 "逼·" 会只把 "逼" 替换为 "*"，保留 "·"。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return currentMessage;
        }

        String lowerCurrent = ColorCodeUtils.stripAllFormatting(currentMessage).toLowerCase();
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
        if (lowerCurrent.isEmpty() || lowerBanned.isEmpty()) {
            return currentMessage;
        }

        // 只打码位置的"连续匹配段"
        boolean[] toReplace = new boolean[currentMessage.length()];
        int bannedIdx = lowerBanned.length() - 1;
        boolean foundAny = false;

        for (int i = lowerCurrent.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (lowerCurrent.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                if (foundAny) break;
            }
        }

        if (!foundAny) {
            return currentMessage;
        }
        StringBuilder sb = new StringBuilder(currentMessage.length());
        for (int i = 0; i < currentMessage.length(); i++) {
            sb.append(toReplace[i] ? "*" : currentMessage.charAt(i));
        }
        return sb.toString();
    }

    /** Fabric 命令侧按 tracker 返回的位置索引 1:1 打码（命令文本通常无 MiniMessage）。 */
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
