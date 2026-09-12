package com.momocraft.textfilter;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Plugin(id = "momotextfilter", name = "MoMoTextFilter-Velocity", version = "2.0.5",
        description = "违禁词过滤器插件 (Velocity端)",
        authors = {"MoMoCraft"})
public class TextFilterVelocity {

    private static TextFilterVelocity instance;

    final ProxyServer proxy;
    private final Path dataDirectory;
    private final MiniMessage miniMessage;
    private final Logger logger;
    ConfigManagerVelocity configManager;
    CrossMessageTracker crossMessageTracker;
    private PunishmentManager punishmentManager;

    @Inject
    public TextFilterVelocity(ProxyServer proxy, @DataDirectory Path dataDirectory, Logger logger) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.miniMessage = MiniMessage.miniMessage();
        this.logger = logger;
        instance = this;
    }

    public static TextFilterVelocity getInstance() {
        return instance;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        configManager = new ConfigManagerVelocity(this, dataDirectory);
        crossMessageTracker = new CrossMessageTracker(this);
        punishmentManager = new PunishmentManager(this);
        TextFilterApi.init(this);

        proxy.getScheduler().buildTask(this, () -> {
            crossMessageTracker.cleanupAll();
            punishmentManager.cleanupAll();
        }).repeat(30, TimeUnit.SECONDS).schedule();

        // 延迟注册 ServerShout 消息监听器，确保 ServerShout 已完全初始化
        proxy.getScheduler().buildTask(this, this::registerServerShoutListener)
                .delay(1, TimeUnit.SECONDS).schedule();

        logger.info("MoMoTextFilter-Velocity 插件已启用！");
    }

    /**
     * 注册 ServerShout 消息监听器。
     * ServerShout 仅提供事件端口，由本插件处理违禁词替换。
     * 使用反射 + 动态代理避免直接依赖 Kotlin 运行时。
     */
    private void registerServerShoutListener() {
        if (!proxy.getPluginManager().getPlugin("servershout").isPresent()) {
            logger.info("ServerShout 未安装，跳过 ServerShout 消息监听器注册。");
            return;
        }

        try {
            Object serverShoutInstance = proxy.getPluginManager().getPlugin("servershout").get()
                    .getInstance().orElse(null);
            if (serverShoutInstance == null) {
                logger.warn("ServerShout 实例未就绪，跳过监听器注册。");
                return;
            }

            ClassLoader serverShoutClassLoader = serverShoutInstance.getClass().getClassLoader();

            Class<?> serverShoutApiClass = Class.forName("io.github.theramu.servershout.common.ServerShoutApi", true, serverShoutClassLoader);
            Method getApiMethod = serverShoutApiClass.getMethod("getApi");
            Object api = getApiMethod.invoke(null);

            Class<?> proxyApiClass = Class.forName("io.github.theramu.servershout.common.ServerShoutProxyApi", true, serverShoutClassLoader);
            if (!proxyApiClass.isInstance(api)) {
                logger.warn("ServerShoutApi 不是 ProxyApi 实例，跳过 ServerShout 监听器注册。");
                return;
            }

            Method getShoutChannelServiceMethod = proxyApiClass.getMethod("getShoutChannelService");
            Object shoutChannelService = getShoutChannelServiceMethod.invoke(api);

            Class<?> listenerClass = Class.forName("io.github.theramu.servershout.common.api.event.ShoutMessageListener", true, serverShoutClassLoader);
            Class<?> eventClass = Class.forName("io.github.theramu.servershout.common.api.event.ShoutMessageEvent", true, serverShoutClassLoader);

            Method getSenderUuidMethod = eventClass.getMethod("getSenderUuid");
            Method getSenderNameMethod = eventClass.getMethod("getSenderName");
            Method getContentMethod = eventClass.getMethod("getContent");
            Method setContentMethod = eventClass.getMethod("setContent", String.class);

            Object listenerProxy = Proxy.newProxyInstance(
                    serverShoutClassLoader,
                    new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("onShoutMessage".equals(method.getName()) && args != null && args.length == 1) {
                                Object event = args[0];
                                UUID senderUuid = (UUID) getSenderUuidMethod.invoke(event);
                                String senderName = (String) getSenderNameMethod.invoke(event);
                                String content = (String) getContentMethod.invoke(event);
                                String filtered = TextFilterApi.filterServerShoutMessage(
                                        senderUuid, senderName, content, "servershout"
                                );
                                setContentMethod.invoke(event, filtered);
                            }
                            return null;
                        }
                    }
            );

            Method addMessageListenerMethod = shoutChannelService.getClass().getMethod("addMessageListener", listenerClass);
            addMessageListenerMethod.invoke(shoutChannelService, listenerProxy);

            logger.info("已注册 ServerShout 消息监听器。");
        } catch (Throwable e) {
            logger.warn("注册 ServerShout 监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Subscribe(priority = 100)
    public void onCommandExecute(CommandExecuteEvent event) {
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player)) {
            return;
        }

        String command = event.getCommand();
        if (command == null || command.isEmpty()) {
            return;
        }

        ConfigManagerVelocity config = configManager;
        CommandType cmdType = config.findMatchingCommandType(command);

        if (cmdType == null) {
            return;
        }

        String contextName = config.getContextName(cmdType.getName());
        String extractedMessage = cmdType.extractMessage(command);

        // 0. 第一轮：整体剔除 MiniMessage 标签块检测（含复核循环，直至整剔文本无法再检出）
        BannedWordDetection strippedDetection = filterStrippedTagsWithDetection(extractedMessage, config);
        String baseText = strippedDetection.hasDetectedWords() ? strippedDetection.getFilteredText() : extractedMessage;

        // 1. 常规检测（连续字符 > 跨字符）在复原标签结构后的文本上继续：先完成检测与替换
        BannedWordDetection detection = filterTextWithDetection(baseText, config);
        String filteredMessage = detection.getFilteredText();
        boolean singleHit = !baseText.equals(filteredMessage);

        // 2. 单消息处理完毕后再做跨消息检测：
        //    以替换后的文本为准（被打码的字符视为不存在），因此已被打码的字符不会再参与跨消息匹配
        CrossMessageTracker.TrackingResult trackingResult = crossMessageTracker.checkAndTrack(player, filteredMessage, contextName);
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

            if (!extractedMessage.equals(filteredMessage)) {
                String newCommand = cmdType.replaceMessage(command, filteredMessage);
                if (!command.equals(newCommand)) {
                    event.setResult(CommandExecuteEvent.CommandResult.command(newCommand));
                }
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
            sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel(), allDetected);
        } else if (singleHit || strippedDetection.hasDetectedWords()) {
            BannedWordDetection primary = strippedDetection.hasDetectedWords() ? strippedDetection : detection;
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            Set<String> addedKeys = new HashSet<>();
            mergeDetected(allDetected, addedKeys, primary.getDetectedWords());
            mergeDetected(allDetected, addedKeys, primary == strippedDetection ? detection.getDetectedWords() : strippedDetection.getDetectedWords());
            sendWarnings(player, contextName, primary.getFirstBannedWord(), primary.getFirstLevel(), allDetected);
        }
    }

    @Subscribe(priority = 100)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message == null || message.isEmpty()) {
            return;
        }

        ConfigManagerVelocity config = configManager;
        CommandType cmdType = config.findMatchingCommandType(message);

        // 只处理前缀模式的消息（非 / 开头的命令）
        if (cmdType == null || !cmdType.isPrefixMode()) {
            return;
        }

        String contextName = config.getContextName(cmdType.getName());
        String extractedMessage = cmdType.extractMessage(message);

        // 0. 第一轮：整体剔除 MiniMessage 标签块检测（含复核循环，直至整剔文本无法再检出）
        BannedWordDetection strippedDetection = filterStrippedTagsWithDetection(extractedMessage, config);
        String baseText = strippedDetection.hasDetectedWords() ? strippedDetection.getFilteredText() : extractedMessage;

        // 1. 常规检测（连续字符 > 跨字符）在复原标签结构后的文本上继续：先完成检测与替换
        BannedWordDetection detection = filterTextWithDetection(baseText, config);
        String filteredMessage = detection.getFilteredText();
        boolean singleHit = !baseText.equals(filteredMessage);

        // 2. 单消息处理完毕后再做跨消息检测：
        //    以替换后的文本为准（被打码的字符视为不存在），因此已被打码的字符不会再参与跨消息匹配
        CrossMessageTracker.TrackingResult trackingResult = crossMessageTracker.checkAndTrack(player, filteredMessage, contextName);
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

            if (!extractedMessage.equals(filteredMessage)) {
                String newMessage = cmdType.replaceMessage(message, filteredMessage);
                if (!message.equals(newMessage)) {
                    event.setResult(PlayerChatEvent.ChatResult.message(newMessage));
                }
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
            sendWarnings(player, contextName, trackingResult.getBannedWord(), trackingResult.getLevel(), allDetected);
        } else if (singleHit || strippedDetection.hasDetectedWords()) {
            BannedWordDetection primary = strippedDetection.hasDetectedWords() ? strippedDetection : detection;
            List<BannedWordDetection.BannedWordInfo> allDetected = new ArrayList<>();
            Set<String> addedKeys = new HashSet<>();
            mergeDetected(allDetected, addedKeys, primary.getDetectedWords());
            mergeDetected(allDetected, addedKeys, primary == strippedDetection ? detection.getDetectedWords() : strippedDetection.getDetectedWords());
            sendWarnings(player, contextName, primary.getFirstBannedWord(), primary.getFirstLevel(), allDetected);
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

    private BannedWordDetection filterTextWithDetection(String text, ConfigManagerVelocity config) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits defaultLimits = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        // 使用 filterAllWithRecheck：替换后继续复核，满足 "傻他妈的逼" -> "*他妈的*" 后再检出 "他妈"
        return ColorCodeUtils.filterAllWithRecheck(text, config.getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
    }

    /** 第一轮：整体剔除 MiniMessage 标签块后的检测（例：傻<click:run_command:/say 一>逼</click> 整剔为 "傻逼"）。 */
    private BannedWordDetection filterStrippedTagsWithDetection(String text, ConfigManagerVelocity config) {
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

    /** Velocity 侧优先按 CrossMessageTracker 计算出的整剔域索引定点打码。 */
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

    public void sendWarnings(Player player, String context) {
        sendWarnings(player, context, "", "");
    }

    public void sendWarnings(Player player, String context, String bannedWord, String level) {
        sendWarnings(player, context, bannedWord, level, null);
    }

    public void sendWarnings(Player player, String context, String bannedWord, String level, List<BannedWordDetection.BannedWordInfo> allDetectedWords) {
        String playerWarning = getPlayerWarningForLevel(level);
        Component playerMessage = miniMessage.deserialize(playerWarning);
        player.sendMessage(playerMessage);

        Map<String, String> adminPlaceholders = new HashMap<>();
        adminPlaceholders.put("player", player.getUsername());
        adminPlaceholders.put("context", context);
        
        if (allDetectedWords != null && !allDetectedWords.isEmpty()) {
            List<String> words = new ArrayList<>();
            for (BannedWordDetection.BannedWordInfo info : allDetectedWords) {
                words.add(info.getWord());
            }
            adminPlaceholders.put("bannedword", String.join(", ", words));
        } else {
            adminPlaceholders.put("bannedword", bannedWord);
        }
        
        adminPlaceholders.put("level", level);
        String adminWarning = getAdminWarningForLevel(level, adminPlaceholders);
        Component adminMessage = miniMessage.deserialize(adminWarning);

        for (Player onlinePlayer : proxy.getAllPlayers()) {
            if (onlinePlayer.hasPermission("textfilter.admin")) {
                onlinePlayer.sendMessage(adminMessage);
            }
        }

        if (level != null && !level.isEmpty()) {
            punishmentManager.onTriggered(player, level);
        }
    }

    private String getPlayerWarningForLevel(String level) {
        if (level != null && !level.isEmpty()) {
            String levelWarning = configManager.getMessage("levels." + level + ".player-warning");
            if (!levelWarning.equals("levels." + level + ".player-warning")) {
                return levelWarning;
            }
        }
        return configManager.getMessage("warnings.player-warning");
    }

    private String getAdminWarningForLevel(String level, Map<String, String> placeholders) {
        if (level != null && !level.isEmpty()) {
            String levelWarning = configManager.getMessage("levels." + level + ".admin-warning", placeholders);
            if (!levelWarning.equals("levels." + level + ".admin-warning")) {
                return levelWarning;
            }
        }
        return configManager.getMessage("warnings.admin-warning", placeholders);
    }

    public ProxyServer getProxy() {
        return proxy;
    }

    public Logger getLogger() {
        return logger;
    }

    public ConfigManagerVelocity getConfigManager() {
        return configManager;
    }

    public Path getDataDir() {
        return dataDirectory;
    }
}
