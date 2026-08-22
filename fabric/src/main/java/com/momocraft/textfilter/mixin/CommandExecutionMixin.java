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
                String filteredMessage = replaceCrossMessageBannedWord(extractedMessage, trackingResult.getBannedWord());
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