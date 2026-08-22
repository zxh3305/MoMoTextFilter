package com.momocraft.textfilter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final JavaPlugin plugin;
    private MessageManager messageManager;
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

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        bannedWords = new ArrayList<>();
        bannedWordsByLevel = new HashMap<>();

        ConfigurationSection bannedWordsSection = config.getConfigurationSection("banned-words");
        if (bannedWordsSection != null) {
            for (String level : bannedWordsSection.getKeys(false)) {
                List<String> words = bannedWordsSection.getStringList(level);
                bannedWordsByLevel.put(level, words);
                bannedWords.addAll(words);
            }
        }

        maxCharGapLimitsByLevel = new HashMap<>();
        fuzzyMatchEnable = config.getBoolean("fuzzy-match.enable", true);
        reverseMatchEnable = config.getBoolean("reverse-match.enable", true);

        maxChatboxGapByLevel = new HashMap<>();
        ConfigurationSection fuzzyLevelsSection = config.getConfigurationSection("fuzzy-match.levels");
        if (fuzzyLevelsSection != null) {
            for (String level : fuzzyLevelsSection.getKeys(false)) {
                maxCharGapLimitsByLevel.put(level, parseCharGapLimits(fuzzyLevelsSection, level));
                int chatboxGap = fuzzyLevelsSection.getInt(level + ".max-chatbox-gap", 0);
                maxChatboxGapByLevel.put(level, chatboxGap);
            }
        }
        defaultMaxCharGap = CharGapLimits.uniform(2);

        reverseMatchByLevel = new HashMap<>();
        ConfigurationSection reverseLevelsSection = config.getConfigurationSection("reverse-match.levels");
        if (reverseLevelsSection != null) {
            for (String level : reverseLevelsSection.getKeys(false)) {
                boolean enable = reverseLevelsSection.getBoolean(level, true);
                reverseMatchByLevel.put(level, enable);
            }
        }

        language = config.getString("language", "zh_CN");
        if (messageManager == null) {
            messageManager = new MessageManager(plugin);
        }
        messageManager.loadMessages(language);

        whitelist = config.getStringList("whitelist");
        if (whitelist == null) {
            whitelist = new ArrayList<>();
        }

        punishCommands = new HashMap<>();
        ConfigurationSection punishSection = config.getConfigurationSection("punish-commands.levels");
        if (punishSection != null) {
            for (String level : punishSection.getKeys(false)) {
                Map<Integer, List<String>> levelCommands = new HashMap<>();
                ConfigurationSection levelSection = punishSection.getConfigurationSection(level);
                if (levelSection != null) {
                    for (String countStr : levelSection.getKeys(false)) {
                        try {
                            int count = Integer.parseInt(countStr);
                            List<String> commands = levelSection.getStringList(countStr);
                            if (!commands.isEmpty()) {
                                levelCommands.put(count, commands);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (!levelCommands.isEmpty()) {
                    punishCommands.put(level, levelCommands);
                }
            }
        }

        commandTypes = new HashMap<>();
        ConfigurationSection externalPluginsSection = config.getConfigurationSection("external-plugins");
        if (externalPluginsSection != null) {
            for (String typeName : externalPluginsSection.getKeys(false)) {
                ConfigurationSection typeSection = externalPluginsSection.getConfigurationSection(typeName);
                if (typeSection != null) {
                    CommandType cmdType = new CommandType(typeName);
                    cmdType.setEnabled(typeSection.getBoolean("enable", true));

                    String argsNumberStr = typeSection.getString("args-number", "");
                    if (!argsNumberStr.isEmpty()) {
                        List<Integer> argsNumbers = new ArrayList<>();
                        String[] parts = argsNumberStr.split(",");
                        for (String part : parts) {
                            try {
                                int num = Integer.parseInt(part.trim());
                                if (num > 0) {
                                    argsNumbers.add(num);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        cmdType.setArgsNumbers(argsNumbers);
                    }

                    cmdType.setExtendToEnd(typeSection.getBoolean("extend-to-end", false));

                    List<String> commands = typeSection.getStringList("commands");
                    cmdType.setCommands(commands);

                    commandTypes.put(typeName, cmdType);
                }
            }
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

    public MessageManager getMessageManager() {
        return messageManager;
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

    public CommandType getCommandType(String name) {
        return commandTypes.get(name);
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

    /**
     * 解析 max-char-gap 配置，兼容两种格式：
     * <pre>
     *   max-char-gap: 2                                   # 旧版单值，三类上限相同
     *   max-char-gap:                                     # 新版按类型分别设置
     *     chinese: 3
     *     english: 50
     *     others: 300
     * </pre>
     */
    private static CharGapLimits parseCharGapLimits(ConfigurationSection fuzzyLevelsSection, String level) {
        ConfigurationSection gapSection = fuzzyLevelsSection.getConfigurationSection(level + ".max-char-gap");
        if (gapSection != null) {
            int chinese = gapSection.getInt("chinese", 2);
            int english = gapSection.getInt("english", 2);
            int others = gapSection.getInt("others", 2);
            return new CharGapLimits(chinese, english, others);
        }
        return CharGapLimits.uniform(fuzzyLevelsSection.getInt(level + ".max-char-gap", 2));
    }
}