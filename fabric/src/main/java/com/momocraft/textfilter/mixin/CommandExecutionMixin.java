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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        // 0. 第一轮：整体剔除 MiniMessage 标签块检测（含复核循环，直至整剔文本无法再检出）
        //    例：傻<click:run_command:/say 一二三>逼</click> 整剔后为 "傻逼" → 命中打码 → *<click:...>*</click>
        BannedWordDetection strippedDetection = filterStrippedTagsWithDetection(extractedMessage, config);
        String baseText = strippedDetection.hasDetectedWords() ? strippedDetection.getFilteredText() : extractedMessage;

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
                filteredMessage = replaceCrossMessageBannedWord(filteredMessage, trackingResult.getBannedWord(), config.isFuzzyMatchEnable(),
                        config.getDefaultMaxCharGap());
            }

            // 跨消息替换后复核，防止打码后剩余字符组合出新的违禁词
            recheckDetection = filterTextWithDetection(filteredMessage, config);
            if (!filteredMessage.equals(recheckDetection.getFilteredText())) {
                filteredMessage = recheckDetection.getFilteredText();
            }
        }

        if (!extractedMessage.equals(filteredMessage)) {
            String newCommand = cmdType.replaceMessage(command, filteredMessage);
            if (!command.equals(newCommand)) {
                executeFiltered(newCommand, source, ci);
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

    /** 命令文本的跨消息替换（fuzzy 夹字匹配）：在指定视图（整剔/载荷）末尾向左扫描 bannedWord 后缀，
     *  中间非命中字符计 gap，任一类超限即停止。返回 null 表示该视图未命中。 */
    private String replaceCrossSuffixInView(String currentMessage, String bannedWord, boolean strippedView,
            boolean fuzzyMatch, CharGapLimits limits) {
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
        int chineseGap = 0, englishGap = 0, othersGap = 0;

        for (int i = viewText.length() - 1; i >= 0 && bannedIdx >= 0; i--) {
            if (viewText.charAt(i) == lowerBanned.charAt(bannedIdx)) {
                toReplace[i] = true;
                bannedIdx--;
                foundAny = true;
            } else {
                if (!foundAny) continue; // 没开始匹配前允许跳过末端的无关字符
                if (fuzzyMatch) {
                    switch (CharacterMapper.classify(viewText.charAt(i))) {
                        case CHINESE: chineseGap++; break;
                        case ENGLISH: englishGap++; break;
                        default: othersGap++; break;
                    }
                    if (chineseGap > limits.chinese || englishGap > limits.english || othersGap > limits.others) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        if (!foundAny) return null;
        return strippedView ? processor.replaceInOriginalWithStrippedMask(toReplace, "*")
                            : processor.replaceInOriginalWithMask(toReplace, "*");
    }

    /** fallback：整剔域优先扫 bannedWord 后缀（fuzzy 夹字匹配），未命中退回载荷域（复原标签后的常规形态）。 */
    private String replaceCrossMessageBannedWord(String currentMessage, String bannedWord, boolean fuzzyMatch, CharGapLimits limits) {
        String r = replaceCrossSuffixInView(currentMessage, bannedWord, true, fuzzyMatch, limits);
        if (r != null) return r;
        return replaceCrossSuffixInView(currentMessage, bannedWord, false, fuzzyMatch, limits);
    }

    /** 按整剔视图索引直接打码（与 tracker 的 strippedText 匹配域对齐，标签结构保留）。 */
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
