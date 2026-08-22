package com.momocraft.textfilter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 字符映射表工具类。
 * 从 charmap 目录下的映射表文件加载等价字符组，
 * 将同一行的所有字符视为等价，映射到第一个基准字符。
 * 例如 "ｆｕｃｋ" 会被映射为 "fuck"，"你媽" 映射为 "你妈"。
 * <p>
 * 同时提供字符分类能力（{@link #classify(char)}），
 * 用于模糊匹配时按字符类型（中文/英文/其他）分别统计间隔字符数量。
 */
public class CharacterMapper {

    /** 字符类型：中文、英文、其他（数字/标点/符号等） */
    public enum CharType { CHINESE, ENGLISH, OTHERS }

    private static final Map<Character, Character> CHAR_MAP = new HashMap<>();
    private static final Set<Character> CHINESE_CHARS = new HashSet<>();
    private static final Set<Character> ENGLISH_CHARS = new HashSet<>();
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
        boolean isChineseFile = resourcePath.contains("中文");
        boolean isEnglishFile = resourcePath.contains("英文");
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
                    parseLine(line, isChineseFile, isEnglishFile);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void parseLine(String line, boolean isChineseFile, boolean isEnglishFile) {
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
                    // 同时登记大小写两种形式，保证分类时对大小写不敏感
                    if (isChineseFile) {
                        CHINESE_CHARS.add(c);
                        CHINESE_CHARS.add(Character.toLowerCase(c));
                        CHINESE_CHARS.add(Character.toUpperCase(c));
                    } else if (isEnglishFile) {
                        ENGLISH_CHARS.add(c);
                        ENGLISH_CHARS.add(Character.toLowerCase(c));
                        ENGLISH_CHARS.add(Character.toUpperCase(c));
                    }
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

    /**
     * 分类一个字符的类型（用于按类型统计模糊匹配的间隔字符）。
     * 中文一二级字表内的字符（含常见 CJK 区间回退）视为中文，
     * 英文字表内的字符（含 ASCII 字母回退）视为英文，
     * 其余（数字、标点、符号、表情等）视为其他。
     */
    public static CharType classify(char c) {
        if (!initialized) {
            initialize();
        }
        if (CHINESE_CHARS.contains(c) || isCjk(c)) {
            return CharType.CHINESE;
        }
        if (isAsciiLetter(c) || ENGLISH_CHARS.contains(c)) {
            return CharType.ENGLISH;
        }
        return CharType.OTHERS;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)   // CJK 扩展A
                || (c >= 0x4E00 && c <= 0x9FFF) // CJK 统一表意文字
                || (c >= 0xF900 && c <= 0xFAFF); // CJK 兼容表意文字
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static int getMappingCount() {
        if (!initialized) initialize();
        return CHAR_MAP.size();
    }
}
