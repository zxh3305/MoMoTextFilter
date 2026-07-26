package com.momocraft.textfilter;

import org.spongepowered.configurate.CommentedConfigurationNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManagerVelocity {

    private final TextFilterVelocity plugin;
    private final Path configPath;
    private MessageManager messageManager;
    private String language;
    private List<String> bannedWords;
    private Map<String, List<String>> bannedWordsByLevel;
    private Map<String, Integer> maxCharGapByLevel;
    private boolean fuzzyMatchEnable;
    private boolean reverseMatchEnable;
    private Map<String, Boolean> reverseMatchByLevel;
    private int defaultMaxCharGap;
    private Map<String, Integer> maxChatboxGapByLevel;
    private List<String> whitelist;
    private Map<String, Map<Integer, List<String>>> punishCommands;
    private Map<String, CommandType> commandTypes;

    public ConfigManagerVelocity(TextFilterVelocity plugin, Path dataDirectory) {
        this.plugin = plugin;
        this.configPath = dataDirectory.resolve("config.yml");
        loadConfig();
    }

    public void loadConfig() {
        try {
            if (!Files.exists(configPath.getParent())) {
                Files.createDirectories(configPath.getParent());
            }

            if (!Files.exists(configPath)) {
                createDefaultConfig();
            }

            var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                    .path(configPath)
                    .build();

            CommentedConfigurationNode root = loader.load();
            loadFromNode(root);

        } catch (Exception e) {
            plugin.getLogger().error("加载配置文件失败", e);
        }
    }

    private void createDefaultConfig() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            if (input != null) {
                Files.copy(input, configPath);
            }
        }
    }

    private void loadFromNode(CommentedConfigurationNode root) {
        bannedWords = new ArrayList<>();
        bannedWordsByLevel = new HashMap<>();

        CommentedConfigurationNode bannedWordsSection = root.node("banned-words");
        if (!bannedWordsSection.isNull()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : bannedWordsSection.childrenMap().entrySet()) {
                String level = entry.getKey().toString();
                List<String> words = new ArrayList<>();
                for (CommentedConfigurationNode wordNode : entry.getValue().childrenList()) {
                    words.add(wordNode.getString(""));
                }
                bannedWordsByLevel.put(level, words);
                bannedWords.addAll(words);
            }
        }

        maxCharGapByLevel = new HashMap<>();
        fuzzyMatchEnable = root.node("fuzzy-match", "enable").getBoolean(true);
        reverseMatchEnable = root.node("reverse-match", "enable").getBoolean(true);

        maxChatboxGapByLevel = new HashMap<>();
        CommentedConfigurationNode fuzzyLevelsSection = root.node("fuzzy-match", "levels");
        if (!fuzzyLevelsSection.isNull()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : fuzzyLevelsSection.childrenMap().entrySet()) {
                String level = entry.getKey().toString();
                int charGap = entry.getValue().node("max-char-gap").getInt(2);
                maxCharGapByLevel.put(level, charGap);
                int chatboxGap = entry.getValue().node("max-chatbox-gap").getInt(0);
                maxChatboxGapByLevel.put(level, chatboxGap);
            }
        }
        defaultMaxCharGap = 2;

        reverseMatchByLevel = new HashMap<>();
        CommentedConfigurationNode reverseLevelsSection = root.node("reverse-match", "levels");
        if (!reverseLevelsSection.isNull()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : reverseLevelsSection.childrenMap().entrySet()) {
                String level = entry.getKey().toString();
                boolean enable = entry.getValue().getBoolean(true);
                reverseMatchByLevel.put(level, enable);
            }
        }

        language = root.node("language").getString("zh_CN");
        if (messageManager == null) {
            messageManager = new MessageManager(plugin);
        }
        messageManager.loadMessages(language);

        whitelist = new ArrayList<>();
        for (CommentedConfigurationNode wordNode : root.node("whitelist").childrenList()) {
            whitelist.add(wordNode.getString(""));
        }
        if (whitelist == null) {
            whitelist = new ArrayList<>();
        }

        punishCommands = new HashMap<>();
        CommentedConfigurationNode punishSection = root.node("punish-commands", "levels");
        if (!punishSection.isNull()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> levelEntry : punishSection.childrenMap().entrySet()) {
                String level = levelEntry.getKey().toString();
                Map<Integer, List<String>> levelCommands = new HashMap<>();
                CommentedConfigurationNode levelSection = levelEntry.getValue();
                for (Map.Entry<Object, ? extends CommentedConfigurationNode> countEntry : levelSection.childrenMap().entrySet()) {
                    String countStr = countEntry.getKey().toString();
                    try {
                        int count = Integer.parseInt(countStr);
                        List<String> commands = new ArrayList<>();
                        for (CommentedConfigurationNode cmdNode : countEntry.getValue().childrenList()) {
                            commands.add(cmdNode.getString(""));
                        }
                        if (!commands.isEmpty()) {
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

        commandTypes = new HashMap<>();
        CommentedConfigurationNode externalPluginsSection = root.node("external-plugins");
        if (!externalPluginsSection.isNull()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> typeEntry : externalPluginsSection.childrenMap().entrySet()) {
                String typeName = typeEntry.getKey().toString();
                CommentedConfigurationNode typeSection = typeEntry.getValue();

                CommandType cmdType = new CommandType(typeName);
                cmdType.setEnabled(typeSection.node("enable").getBoolean(true));

                String argsNumberStr = typeSection.node("args-number").getString("");
                if (argsNumberStr != null && !argsNumberStr.isEmpty()) {
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

                cmdType.setExtendToEnd(typeSection.node("extend-to-end").getBoolean(false));

                List<String> commands = new ArrayList<>();
                for (CommentedConfigurationNode cmdNode : typeSection.node("commands").childrenList()) {
                    commands.add(cmdNode.getString(""));
                }
                cmdType.setCommands(commands);

                // 加载前缀列表（用于匹配非 / 开头的消息，如 "!"）
                List<String> prefixes = new ArrayList<>();
                for (CommentedConfigurationNode prefixNode : typeSection.node("prefixes").childrenList()) {
                    prefixes.add(prefixNode.getString(""));
                }
                cmdType.setPrefixes(prefixes);

                commandTypes.put(typeName, cmdType);
            }
        }
    }

    public List<String> getBannedWords() {
        return bannedWords != null ? bannedWords : new ArrayList<>();
    }

    public Map<String, List<String>> getBannedWordsByLevel() {
        return bannedWordsByLevel != null ? bannedWordsByLevel : new HashMap<>();
    }

    public int getMaxCharGapForLevel(String level) {
        return maxCharGapByLevel.getOrDefault(level, defaultMaxCharGap);
    }

    public Map<String, Integer> getMaxCharGapByLevel() {
        return maxCharGapByLevel != null ? maxCharGapByLevel : new HashMap<>();
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

    public int getDefaultMaxCharGap() {
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

    public boolean isReverseMatchEnableForLevel(String level) {
        return reverseMatchByLevel.getOrDefault(level, true);
    }

    public Map<String, Boolean> getReverseMatchByLevel() {
        return reverseMatchByLevel != null ? reverseMatchByLevel : new HashMap<>();
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
}
