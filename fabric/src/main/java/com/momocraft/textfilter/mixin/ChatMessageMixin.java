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
                int[] positions = trackingResult.getMatchedPositionsInCurrent();
                if (positions != null && positions.length > 0) {
                    filteredMessage = replaceCrossByPositions(message, positions);
                } else {
                    filteredMessage = replaceCrossMessageBannedWord(message, trackingResult.getBannedWord());
                }
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

    /** 跨消息匹配成功后，替换当前消息中匹配到的违禁词后缀字符。
     *  从 processedText 末尾向左查找"与 bannedWord 后缀能连续匹配"的字符段，
     *  遇不匹配字符立即停止 —— 这样 "逼·" 会只把 "逼" 标记为替换，保留 "·"。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return currentMessage;
        }

        TextProcessor processor = new TextProcessor(currentMessage);
        String processedText = CharacterMapper.normalize(processor.getProcessedText().toLowerCase());
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
        if (processedText.isEmpty() || lowerBanned.isEmpty()) {
            return currentMessage;
        }

        boolean[] toReplace = new boolean[processedText.length()];
        int bannedIdx = lowerBanned.length() - 1;
        boolean foundAny = false;

        for (int i = processedText.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (processedText.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                if (foundAny) break;
            }
        }

        if (foundAny) {
            return processor.replaceInOriginalWithMask(toReplace, "*");
        }
        return currentMessage;
    }

    /** Fabric 聊天侧优先按 tracker 给出的 processedText 索引定点打码。 */
    private String replaceCrossByPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        TextProcessor processor = new TextProcessor(currentMessage);
        int len = processor.getProcessedText() == null ? 0 : processor.getProcessedText().length();
        if (len == 0) return currentMessage;
        boolean[] toReplace = new boolean[len];
        for (int p : positions) {
            if (p >= 0 && p < len) toReplace[p] = true;
        }
        return processor.replaceInOriginalWithMask(toReplace, "*");
    }
}
