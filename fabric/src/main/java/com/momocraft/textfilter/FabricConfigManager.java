package com.momocraft.textfilter;

import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class FabricConfigManager {

    /** 资源唯一路径前缀，避免与其它 mod 的根路径资源(/config.yml)冲突 */
    private static final String RESOURCE_PREFIX = "/momotextfilter/";
    private static final String CONFIG_RESOURCE = RESOURCE_PREFIX + "config.yml";

    private Path configDir;
    private Path configFile;
    private FabricMessageManager messageManager;
    private String language;
    private List<String> bannedWords;
    private Map<String, List<String>> bannedWordsByLevel;
    private Map<String, CharGapLimits> maxCharGapLimitsByLevel;
    private boolean fuzzyMatchEnable;
    private boolean reverseMatchEnable;
    private Map<String, Boolean> reverseMatchByLevel;
    private CharGapLimits defaultMaxCharGap;
    private Map<String, Integer> maxChatboxGapByLevel;
    private List<String> whitelist;
    private Map<String, Map<Integer, List<String>>> punishCommands;
    private Map<String, CommandType> commandTypes;

    public FabricConfigManager() {
        // 使用 Fabric 标准配置目录: <server>/config/MoMoTextFilter/
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve("MoMoTextFilter");
        this.configFile = configDir.resolve("config.yml");
    }

    @SuppressWarnings("unchecked")
    public void loadConfig() {
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(configFile)) {
                try (InputStream is = getClass().getResourceAsStream(CONFIG_RESOURCE)) {
                    if (is != null) {
                        Files.copy(is, configFile);
                        MoMoTextFilterMod.LOGGER.info("已生成默认配置: {}", configFile.toAbsolutePath());
                    } else {
                        MoMoTextFilterMod.LOGGER.error("未找到内置默认配置资源: {}", CONFIG_RESOURCE);
                    }
                }
            }
        } catch (IOException e) {
            MoMoTextFilterMod.LOGGER.error("Failed to create config directory", e);
        }

        Map<String, Object> config = loadYaml(configFile);

        bannedWords = new ArrayList<>();
        bannedWordsByLevel = new HashMap<>();

        Map<String, Object> bannedWordsSection = (Map<String, Object>) config.get("banned-words");
        if (bannedWordsSection != null) {
            for (Map.Entry<String, Object> entry : bannedWordsSection.entrySet()) {
                String level = entry.getKey();
                List<String> words = (List<String>) entry.getValue();
                if (words != null) {
                    bannedWordsByLevel.put(level, new ArrayList<>(words));
                    bannedWords.addAll(words);
                }
            }
        }

        maxCharGapLimitsByLevel = new HashMap<>();
        Map<String, Object> fuzzyMatch = (Map<String, Object>) config.get("fuzzy-match");
        fuzzyMatchEnable = fuzzyMatch != null && (Boolean) fuzzyMatch.getOrDefault("enable", true);

        reverseMatchByLevel = new HashMap<>();
        Map<String, Object> reverseMatch = (Map<String, Object>) config.get("reverse-match");
        reverseMatchEnable = reverseMatch != null && (Boolean) reverseMatch.getOrDefault("enable", true);

        maxChatboxGapByLevel = new HashMap<>();
        if (fuzzyMatch != null) {
            Map<String, Object> fuzzyLevels = (Map<String, Object>) fuzzyMatch.get("levels");
            if (fuzzyLevels != null) {
                for (Map.Entry<String, Object> entry : fuzzyLevels.entrySet()) {
                    String level = entry.getKey();
                    Map<String, Object> levelConfig = (Map<String, Object>) entry.getValue();
                    if (levelConfig != null) {
                        maxCharGapLimitsByLevel.put(level, parseCharGapLimits(levelConfig));
                        Object chatboxGap = levelConfig.get("max-chatbox-gap");
                        maxChatboxGapByLevel.put(level, chatboxGap instanceof Number ? ((Number) chatboxGap).intValue() : 0);
                    }
                }
            }
        }
        defaultMaxCharGap = CharGapLimits.uniform(2);

        if (reverseMatch != null) {
            Map<String, Object> reverseLevels = (Map<String, Object>) reverseMatch.get("levels");
            if (reverseLevels != null) {
                for (Map.Entry<String, Object> entry : reverseLevels.entrySet()) {
                    reverseMatchByLevel.put(entry.getKey(), (Boolean) entry.getValue());
                }
            }
        }

        language = (String) config.getOrDefault("language", "zh_CN");
        if (messageManager == null) {
            messageManager = new FabricMessageManager(configDir);
        }
        messageManager.loadMessages(language);

        whitelist = (List<String>) config.get("whitelist");
        if (whitelist == null) {
            whitelist = new ArrayList<>();
        }

        punishCommands = new HashMap<>();
        Map<String, Object> punishSection = (Map<String, Object>) config.get("punish-commands");
        if (punishSection != null) {
            Map<String, Object> punishLevels = (Map<String, Object>) punishSection.get("levels");
            if (punishLevels != null) {
                for (Map.Entry<String, Object> entry : punishLevels.entrySet()) {
                    String level = entry.getKey();
                    Map<String, Object> levelSection = (Map<String, Object>) entry.getValue();
                    if (levelSection != null) {
                        Map<Integer, List<String>> levelCommands = new HashMap<>();
                        // 注意：SnakeYAML 会把 "3:" "5:" 这类数字键解析为 Integer 而非 String，
                        // 必须用通配符遍历 + String.valueOf 转换，避免 ClassCastException
                        for (Map.Entry<?, ?> countEntry : levelSection.entrySet()) {
                            try {
                                int count = Integer.parseInt(String.valueOf(countEntry.getKey()));
                                List<String> commands = (List<String>) countEntry.getValue();
                                if (commands != null && !commands.isEmpty()) {
                                    levelCommands.put(count, commands);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (!levelCommands.isEmpty()) {
                            punishCommands.put(level, levelCommands);
                        }
                    }
                }
            }
        }

        commandTypes = new HashMap<>();
        Map<String, Object> externalPlugins = (Map<String, Object>) config.get("external-plugins");
        if (externalPlugins != null) {
            for (Map.Entry<String, Object> entry : externalPlugins.entrySet()) {
                String typeName = entry.getKey();
                Map<String, Object> typeSection = (Map<String, Object>) entry.getValue();
                if (typeSection != null) {
                    CommandType cmdType = new CommandType(typeName);
                    cmdType.setEnabled((Boolean) typeSection.getOrDefault("enable", true));

                    Object argsNumberObj = typeSection.get("args-number");
                    if (argsNumberObj != null) {
                        List<Integer> argsNumbers = new ArrayList<>();
                        if (argsNumberObj instanceof String && !((String) argsNumberObj).isEmpty()) {
                            String[] parts = ((String) argsNumberObj).split(",");
                            for (String part : parts) {
                                try {
                                    int num = Integer.parseInt(part.trim());
                                    if (num > 0) {
                                        argsNumbers.add(num);
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        } else if (argsNumberObj instanceof Number) {
                            int num = ((Number) argsNumberObj).intValue();
                            if (num > 0) {
                                argsNumbers.add(num);
                            }
                        }
                        cmdType.setArgsNumbers(argsNumbers);
                    }

                    cmdType.setExtendToEnd((Boolean) typeSection.getOrDefault("extend-to-end", false));

                    List<String> commands = (List<String>) typeSection.get("commands");
                    cmdType.setCommands(commands != null ? commands : new ArrayList<>());

                    commandTypes.put(typeName, cmdType);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static CharGapLimits parseCharGapLimits(Map<String, Object> levelConfig) {
        Object gapObj = levelConfig.get("max-char-gap");
        if (gapObj instanceof Map) {
            Map<String, Object> gapMap = (Map<String, Object>) gapObj;
            int chinese = ((Number) gapMap.getOrDefault("chinese", 2)).intValue();
            int english = ((Number) gapMap.getOrDefault("english", 2)).intValue();
            int others = ((Number) gapMap.getOrDefault("others", 2)).intValue();
            return new CharGapLimits(chinese, english, others);
        }
        if (gapObj instanceof Number) {
            return CharGapLimits.uniform(((Number) gapObj).intValue());
        }
        return CharGapLimits.uniform(2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path file) {
        try {
            DumperOptions options = new DumperOptions();
            Representer representer = new Representer(options);
            Yaml yaml = new Yaml(representer);
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                Map<String, Object> result = yaml.load(reader);
                return result != null ? result : new HashMap<>();
            }
        } catch (IOException e) {
            MoMoTextFilterMod.LOGGER.error("Failed to load YAML file: " + file, e);
            return new HashMap<>();
        }
    }

    public List<String> getBannedWords() {
        return bannedWords != null ? bannedWords : new ArrayList<>();
    }

    public Map<String, List<String>> getBannedWordsByLevel() {
        return bannedWordsByLevel != null ? bannedWordsByLevel : new HashMap<>();
    }

    public CharGapLimits getMaxCharGapForLevel(String level) {
        return maxCharGapLimitsByLevel.getOrDefault(level, defaultMaxCharGap);
    }

    public Map<String, CharGapLimits> getMaxCharGapByLevel() {
        return maxCharGapLimitsByLevel != null ? maxCharGapLimitsByLevel : new HashMap<>();
    }

    public boolean isReverseMatchEnableForLevel(String level) {
        return reverseMatchByLevel.getOrDefault(level, true);
    }

    public Map<String, Boolean> getReverseMatchByLevel() {
        return reverseMatchByLevel != null ? reverseMatchByLevel : new HashMap<>();
    }

    public String getMessage(String key) {
        return messageManager != null ? messageManager.getMessage(key) : key;
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        return messageManager != null ? messageManager.getMessage(key, placeholders) : key;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isFuzzyMatchEnable() {
        return fuzzyMatchEnable;
    }

    public CharGapLimits getDefaultMaxCharGap() {
        return defaultMaxCharGap;
    }

    public int getChatboxGapForLevel(String level) {
        return maxChatboxGapByLevel.getOrDefault(level, 0);
    }

    public Map<String, Integer> getMaxChatboxGapByLevel() {
        return maxChatboxGapByLevel != null ? maxChatboxGapByLevel : new HashMap<>();
    }

    public boolean isReverseMatchEnable() {
        return reverseMatchEnable;
    }

    public List<String> getWhitelist() {
        return whitelist != null ? whitelist : new ArrayList<>();
    }

    public Map<String, Map<Integer, List<String>>> getPunishCommands() {
        return punishCommands != null ? punishCommands : new HashMap<>();
    }

    public Map<String, CommandType> getCommandTypes() {
        return commandTypes != null ? commandTypes : new HashMap<>();
    }

    public CommandType findMatchingCommandType(String message) {
        for (CommandType cmdType : commandTypes.values()) {
            if (cmdType.isEnabled() && cmdType.matchesCommand(message)) {
                return cmdType;
            }
        }
        return null;
    }

    public String getContextName(String typeName) {
        return messageManager.getMessage("context-names." + typeName, typeName);
    }
}