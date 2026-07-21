package com.momocraft.textfilter;

import com.velocitypowered.api.proxy.ProxyServer;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final TextFilterVelocity plugin;
    private final Map<String, String> messages;
    private String language;

    public MessageManager(TextFilterVelocity plugin) {
        this.plugin = plugin;
        this.messages = new HashMap<>();
    }

    public void loadMessages(String language) {
        this.language = language;
        messages.clear();

        Path dataFolder = plugin.getDataDir();
        String fileName = "messages_" + language + ".yml";
        Path messageFile = dataFolder.resolve(fileName);

        if (!Files.exists(messageFile)) {
            createDefaultMessageFile(fileName, messageFile);
        }

        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(messageFile)
                    .build();
            CommentedConfigurationNode root = loader.load();
            loadMessagesFromNode(root, "");
        } catch (Exception e) {
            plugin.getLogger().error("加载消息文件失败", e);
            loadDefaultMessages();
        }

        if (messages.isEmpty()) {
            loadDefaultMessages();
        }
    }

    private void createDefaultMessageFile(String fileName, Path messageFile) {
        InputStream is = getClass().getResourceAsStream("/" + fileName);
        if (is != null) {
            try {
                Files.createDirectories(messageFile.getParent());
                Files.copy(is, messageFile);
            } catch (IOException e) {
                plugin.getLogger().error("创建默认消息文件失败: " + fileName, e);
            }
        }
    }

    private void loadMessagesFromNode(CommentedConfigurationNode node, String prefix) {
        for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : node.childrenMap().entrySet()) {
            String key = entry.getKey().toString();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (entry.getValue().isMap()) {
                loadMessagesFromNode(entry.getValue(), fullKey);
            } else {
                messages.put(fullKey, entry.getValue().getString(""));
            }
        }
    }

    private void loadDefaultMessages() {
        InputStream is = getClass().getResourceAsStream("/messages_en_US.yml");
        if (is != null) {
            try {
                YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                        .source(() -> new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
                        .build();
                CommentedConfigurationNode root = loader.load();
                loadMessagesFromNode(root, "");
            } catch (Exception e) {
                plugin.getLogger().error("加载默认消息文件失败", e);
            }
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
