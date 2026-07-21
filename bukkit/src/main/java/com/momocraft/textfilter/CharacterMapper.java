package com.momocraft.textfilter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 字符映射表工具类。
 * 从 charmap 目录下的映射表文件加载等价字符组，
 * 将同一行的所有字符视为等价，映射到第一个基准字符。
 * 例如 "ｆｕｃｋ" 会被映射为 "fuck"，"你媽" 映射为 "你妈"。
 */
public class CharacterMapper {

    private static final Map<Character, Character> CHAR_MAP = new HashMap<>();
    private static volatile boolean initialized = false;

    private static synchronized void initialize() {
        if (initialized) return;

        String[] files = {
            "/charmap/英文.txt",
            "/charmap/数字.txt",
            "/charmap/标点符号.txt",
            "/charmap/中文一级字.txt",
            "/charmap/中文二级字.txt"
        };

        for (String file : files) {
            loadFile(file);
        }

        initialized = true;
    }

    private static void loadFile(String resourcePath) {
        try (InputStream is = CharacterMapper.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                boolean firstLine = true;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (firstLine) {
                        firstLine = false;
                        if (line.startsWith("序号") || line.contains("基准") || line.contains("简体")) {
                            continue;
                        }
                    }
                    parseLine(line);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void parseLine(String line) {
        String[] columns = line.split(",");
        if (columns.length < 2) return;

        Character base = null;
        for (String col : columns) {
            col = col.trim();
            if (col.isEmpty()) continue;
            if (col.matches("\\d+")) continue;

            String[] variants = col.split("/");
            for (String variant : variants) {
                variant = variant.trim();
                if (variant.isEmpty()) continue;
                for (int i = 0; i < variant.length(); i++) {
                    char c = variant.charAt(i);
                    if (base == null) {
                        base = c;
                    }
                    CHAR_MAP.putIfAbsent(c, base);
                }
            }
        }
    }

    public static String normalize(String text) {
        if (!initialized) {
            initialize();
        }
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character mapped = CHAR_MAP.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        return sb.toString();
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static int getMappingCount() {
        if (!initialized) initialize();
        return CHAR_MAP.size();
    }
}
