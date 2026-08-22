package com.momocraft.textfilter.mixin;

import com.momocraft.textfilter.*;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ChatMessageMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        String message = packet.message();
        if (message == null || message.isEmpty()) {
            return;
        }

        MoMoTextFilterMod mod = MoMoTextFilterMod.getInstance();
        if (mod == null) return;

        FabricConfigManager config = mod.getConfigManager();
        String contextName = config.getContextName("chat");

        // Check cross-message tracking first
        CrossMessageTracker.TrackingResult trackingResult = mod.getCrossMessageTracker().checkAndTrack(player, message, contextName);
        if (trackingResult != null) {
            String filteredMessage = message;

            if (trackingResult.isCrossMessageMatch()) {
                filteredMessage = replaceCrossMessageBannedWord(message, trackingResult.getBannedWord());
            }

            BannedWordDetection detection = filterTextWithDetection(filteredMessage, config);
            if (!filteredMessage.equals(detection.getFilteredText())) {
                filteredMessage = detection.getFilteredText();
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

            mod.sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel(), allDetected);

            if (!message.equals(filteredMessage)) {
                ci.cancel();
                ServerboundChatPacket newPacket = new ServerboundChatPacket(filteredMessage,
                    packet.timeStamp(), packet.salt(), packet.signature(), packet.lastSeenMessages());
                // Cast to call original method — no @Shadow on concrete methods
                ((ServerGamePacketListenerImpl)(Object)this).handleChat(newPacket);
            }
            return;
        }

        // Regular detection
        BannedWordDetection detection = filterTextWithDetection(message, config);

        if (!message.equals(detection.getFilteredText())) {
            mod.sendWarnings(player, contextName, detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
            ci.cancel();
            ServerboundChatPacket newPacket = new ServerboundChatPacket(detection.getFilteredText(),
                packet.timeStamp(), packet.salt(), packet.signature(), packet.lastSeenMessages());
            ((ServerGamePacketListenerImpl)(Object)this).handleChat(newPacket);
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