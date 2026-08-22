package com.momocraft.textfilter;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class FabricMessageManager {

    private static final String RESOURCE_PREFIX = "/momotextfilter/";

    private final Path configDir;
    private final Map<String, String> messages;
    private String language;

    public FabricMessageManager(Path configDir) {
        this.configDir = configDir;
        this.messages = new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    public void loadMessages(String language) {
        this.language = language;
        messages.clear();

        String fileName = "messages_" + language + ".yml";
        Path messageFile = configDir.resolve(fileName);

        try {
            if (!Files.exists(messageFile)) {
                // 使用唯一路径前缀，避免与其它 mod 的根路径资源冲突
                try (InputStream is = getClass().getResourceAsStream(RESOURCE_PREFIX + fileName)) {
                    if (is != null) {
                        Files.createDirectories(configDir);
                        Files.copy(is, messageFile);
                        MoMoTextFilterMod.LOGGER.info("已生成默认消息文件: {}", messageFile.toAbsolutePath());
                    } else {
                        MoMoTextFilterMod.LOGGER.warn("未找到内置消息资源: {}", RESOURCE_PREFIX + fileName);
                    }
                }
            }

            if (Files.exists(messageFile)) {
                DumperOptions options = new DumperOptions();
                Representer representer = new Representer(options);
                Yaml yaml = new Yaml(representer);
                try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(messageFile), StandardCharsets.UTF_8)) {
                    Map<String, Object> config = yaml.load(reader);
                    if (config != null) {
                        loadMessagesFromConfig(config, "");
                    }
                }
            }
        } catch (IOException e) {
            MoMoTextFilterMod.LOGGER.error("Failed to load messages file: " + messageFile, e);
        }

        if (messages.isEmpty()) {
            loadDefaultMessages();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadMessagesFromConfig(Map<String, Object> config, String prefix) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (entry.getValue() instanceof Map) {
                loadMessagesFromConfig((Map<String, Object>) entry.getValue(), fullKey);
            } else {
                messages.put(fullKey, entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDefaultMessages() {
        try (InputStream is = getClass().getResourceAsStream(RESOURCE_PREFIX + "messages_en_US.yml")) {
            if (is != null) {
                DumperOptions options = new DumperOptions();
                Representer representer = new Representer(options);
                Yaml yaml = new Yaml(representer);
                Map<String, Object> config = yaml.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                if (config != null) {
                    loadMessagesFromConfig(config, "");
                }
            }
        } catch (IOException e) {
            MoMoTextFilterMod.LOGGER.error("Failed to load default messages", e);
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