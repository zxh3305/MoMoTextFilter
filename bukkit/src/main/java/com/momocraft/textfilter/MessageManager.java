package com.momocraft.textfilter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final JavaPlugin plugin;
    private final Map<String, String> messages;
    private String language;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.messages = new HashMap<>();
    }

    public void loadMessages(String language) {
        this.language = language;
        messages.clear();

        File dataFolder = plugin.getDataFolder();
        String fileName = "messages_" + language + ".yml";
        File messageFile = new File(dataFolder, fileName);

        if (!messageFile.exists()) {
            plugin.saveResource(fileName, false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(messageFile);
        loadMessagesFromConfig(config, "");

        if (messages.isEmpty()) {
            loadDefaultMessages();
        }
    }

    private void loadMessagesFromConfig(ConfigurationSection config, String prefix) {
        for (String key : config.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (config.isConfigurationSection(key)) {
                loadMessagesFromConfig(config.getConfigurationSection(key), fullKey);
            } else {
                messages.put(fullKey, config.getString(key, ""));
            }
        }
    }

    private void loadDefaultMessages() {
        InputStream is = plugin.getResource("messages_en_US.yml");
        if (is != null) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            loadMessagesFromConfig(config, "");
        }
    }

    public String getMessage(String key) {
        return messages.getOrDefault(key, key);
    }

    public String getMessage(String key, String defaultValue) {
        String value = messages.get(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String message = getMessage(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return message;
    }

    public String getLanguage() {
        return language;
    }
}
