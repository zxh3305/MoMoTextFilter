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

    /** 内部重放标记：单消息/跨消息过滤后重发包时直接放行，避免已过滤文本再次进入检测
     *  重复警告或重复建 state。ServerGamePacketListenerImpl 每连接一个实例，
     *  同一连接的包在网络线程串行处理，实例字段安全。 */
    private boolean momotextfilter$replaying = false;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        String message = packet.message();
        if (message == null || message.isEmpty()) {
            return;
        }

        MoMoTextFilterMod mod = MoMoTextFilterMod.getInstance();
        if (mod == null) return;

        // 内部重放的已过滤文本：直接放行，不再检测
        if (momotextfilter$replaying) {
            return;
        }

        FabricConfigManager config = mod.getConfigManager();
        String contextName = config.getContextName("chat");

        // 0. 第一轮：整体剔除 MiniMessage 标签块检测（含复核循环，直至整剔文本无法再检出）
        //    例：傻<click:run_command:/say 一二三>逼</click> 整剔后为 "傻逼" → 命中打码 → *<click:...>*</click>
        BannedWordDetection strippedDetection = filterStrippedTagsWithDetection(message, config);
        String baseText = strippedDetection.hasDetectedWords() ? strippedDetection.getFilteredText() : message;

        // 1. 常规检测（连续字符 > 跨字符）在复原标签结构后的文本上继续：先完成检测与替换
        BannedWordDetection detection = filterTextWithDetection(baseText, config);
        String filteredMessage = detection.getFilteredText();
        boolean singleHit = !baseText.equals(filteredMessage);

        // 2. 单消息处理完毕后再做跨消息检测：
        //    以替换后的文本为准（被打码的字符视为不存在），因此已被打码的字符不会再参与跨消息匹配
        CrossMessageTracker.TrackingResult trackingResult = mod.getCrossMessageTracker().checkAndTrack(player, filteredMessage, contextName);
        BannedWordDetection recheckDetection = null;
        if (trackingResult != null && trackingResult.isCrossMessageMatch()) {
            int[] positions = trackingResult.getMatchedPositionsInCurrent();
            if (positions != null && positions.length > 0) {
                filteredMessage = trackingResult.isPositionsInStrippedView()
                        ? replaceCrossByPositions(filteredMessage, positions)
                        : replaceCrossByProcessedPositions(filteredMessage, positions);
            } else {
                filteredMessage = replaceCrossMessageBannedWord(filteredMessage, trackingResult.getBannedWord());
            }

            // 跨消息替换后复核，防止打码后剩余字符组合出新的违禁词
            recheckDetection = filterTextWithDetection(filteredMessage, config);
            if (!filteredMessage.equals(recheckDetection.getFilteredText())) {
                filteredMessage = recheckDetection.getFilteredText();
            }
        }

        // 3. 合并去重警告：跨消息命中为主，整剔/单消息/复核命中并入
        if (trackingResult != null) {
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            String trackingKey = trackingResult.getBannedWord() + ":" + trackingResult.getLevel();
            Set<String> addedKeys = new HashSet<>();
            addedKeys.add(trackingKey);
            allDetected.add(new BannedWordDetection.BannedWordInfo(trackingResult.getBannedWord(), trackingResult.getLevel()));
            mergeDetected(allDetected, addedKeys, strippedDetection.getDetectedWords());
            mergeDetected(allDetected, addedKeys, singleHit ? detection.getDetectedWords() : null);
            mergeDetected(allDetected, addedKeys, recheckDetection == null ? null : recheckDetection.getDetectedWords());
            mod.sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel(), allDetected);
        } else if (singleHit || strippedDetection.hasDetectedWords()) {
            BannedWordDetection primary = strippedDetection.hasDetectedWords() ? strippedDetection : detection;
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            Set<String> addedKeys = new HashSet<>();
            mergeDetected(allDetected, addedKeys, primary.getDetectedWords());
            mergeDetected(allDetected, addedKeys, primary == strippedDetection ? detection.getDetectedWords() : strippedDetection.getDetectedWords());
            mod.sendWarnings(player, contextName, primary.getFirstBannedWord(), primary.getFirstLevel(), allDetected);
        }

        // 4. 文本有变化时重放已过滤消息（重放直接放行，不再检测）
        if (!message.equals(filteredMessage)) {
            ci.cancel();
            ServerboundChatPacket newPacket = new ServerboundChatPacket(filteredMessage,
                packet.timeStamp(), packet.salt(), packet.signature(), packet.lastSeenMessages());
            momotextfilter$replaying = true;
            try {
                ((ServerGamePacketListenerImpl)(Object)this).handleChat(newPacket);
            } finally {
                momotextfilter$replaying = false;
            }
        }
    }

    /** 将检测结果并入警告列表（按 词:等级 去重）。 */
    private void mergeDetected(List<BannedWordDetection.BannedWordInfo> allDetected, Set<String> addedKeys,
            List<BannedWordDetection.BannedWordInfo> detectedWords) {
        if (detectedWords == null) return;
        for (BannedWordDetection.BannedWordInfo info : detectedWords) {
            String key = info.getWord() + ":" + info.getLevel();
            if (!addedKeys.contains(key)) {
                addedKeys.add(key);
                allDetected.add(info);
            }
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

    /** 第一轮：整体剔除 MiniMessage 标签块后的检测（例：傻<click:run_command:/say 一>逼</click> 整剔为 "傻逼"）。 */
    private BannedWordDetection filterStrippedTagsWithDetection(String text, FabricConfigManager config) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits defaultLimits = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        return ColorCodeUtils.filterStrippedTagsWithDetection(text, config.getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
    }

    /** 跨消息 suffix 打码（strict 连续匹配）：在指定视图（整剔/载荷）末尾向左扫描 bannedWord 后缀，
     *  遇不匹配字符立即停止 —— 只替换"连续后缀匹配段"（如 "逼·" 只打码 "逼"，保留 "·"）。
     *  返回 null 表示该视图未命中。 */
    private String replaceCrossSuffixInView(String currentMessage, String bannedWord, boolean strippedView) {
        if (currentMessage == null || currentMessage.isEmpty() || bannedWord == null || bannedWord.isEmpty()) {
            return null;
        }

        TextProcessor processor = new TextProcessor(currentMessage);
        String view = strippedView ? processor.getStrippedTagsText() : processor.getProcessedText();
        String viewText = CharacterMapper.normalize((view == null ? "" : view).toLowerCase());
        String lowerBanned = CharacterMapper.normalize(bannedWord.toLowerCase());
        if (viewText.isEmpty() || lowerBanned.isEmpty()) {
            return null;
        }

        boolean[] toReplace = new boolean[viewText.length()];
        int bannedIdx = lowerBanned.length() - 1;
        boolean foundAny = false;

        for (int i = viewText.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (viewText.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                // 遇到不匹配字符立即停止；保证只替换"连续后缀匹配段"
                if (foundAny) break;
            }
        }

        if (!foundAny) {
            return null;
        }
        return strippedView ? processor.replaceInOriginalWithStrippedMask(toReplace, "*")
                            : processor.replaceInOriginalWithMask(toReplace, "*");
    }

    /** fallback：整剔域优先扫 bannedWord 后缀，未命中退回载荷域（复原标签后的常规形态）。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord) {
        String r = replaceCrossSuffixInView(currentMessage, bannedWord, true);
        if (r != null) return r;
        return replaceCrossSuffixInView(currentMessage, bannedWord, false);
    }

    /** 优先走这条：CrossMessageTracker 已计算出当前消息内应打码的整剔域索引，直接按位置打码。 */
    private String replaceCrossByPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        TextProcessor processor = new TextProcessor(currentMessage);
        String strippedView = processor.getStrippedTagsText();
        int len = strippedView == null ? 0 : strippedView.length();
        if (len == 0) return currentMessage;
        boolean[] toReplace = new boolean[len];
        for (int p : positions) {
            if (p >= 0 && p < len) toReplace[p] = true;
        }
        return processor.replaceInOriginalWithStrippedMask(toReplace, "*");
    }

    /** tracker 命中于载荷域（processedText）时：按载荷视图索引定点打码。 */
    private String replaceCrossByProcessedPositions(String currentMessage, int[] positions) {
        if (currentMessage == null || positions == null || positions.length == 0) {
            return currentMessage;
        }
        TextProcessor processor = new TextProcessor(currentMessage);
        String view = processor.getProcessedText();
        int len = view == null ? 0 : view.length();
        if (len == 0) return currentMessage;
        boolean[] toReplace = new boolean[len];
        for (int p : positions) {
            if (p >= 0 && p < len) toReplace[p] = true;
        }
        return processor.replaceInOriginalWithMask(toReplace, "*");
    }
}
