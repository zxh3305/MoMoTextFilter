package com.momocraft.textfilter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 MiniMessage 风格的标签格式字符串转换为 Minecraft 原生 {@link Component}。
 * <p>
 * 支持以下标签：red, gold, green, gray, yellow, blue, white, dark_gray, dark_red, dark_green,
 * dark_blue, dark_purple, light_purple, aqua, dark_aqua, black, bold, italic, strikethrough,
 * underlined, obfuscated, reset。
 * 不支持参数化标签（如 &lt;color:#ff0000&gt;），会被静默忽略。
 * 不支持嵌套标签；最新打开的标签会覆盖之前的颜色。
 * 不支持自闭合标签（如 &lt;br/&gt; 或 &lt;reset/&gt;）。
 */
public class ComponentFormatter {

    private static final Pattern TAG_PATTERN = Pattern.compile("</?([a-zA-Z0-9_]+)>");

    private static final Map<String, Style> STYLE_MAP = new HashMap<>();

    static {
        // 颜色
        STYLE_MAP.put("black", Style.EMPTY.withColor(ChatFormatting.BLACK));
        STYLE_MAP.put("dark_blue", Style.EMPTY.withColor(ChatFormatting.DARK_BLUE));
        STYLE_MAP.put("dark_green", Style.EMPTY.withColor(ChatFormatting.DARK_GREEN));
        STYLE_MAP.put("dark_aqua", Style.EMPTY.withColor(ChatFormatting.DARK_AQUA));
        STYLE_MAP.put("dark_red", Style.EMPTY.withColor(ChatFormatting.DARK_RED));
        STYLE_MAP.put("dark_purple", Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE));
        STYLE_MAP.put("gold", Style.EMPTY.withColor(ChatFormatting.GOLD));
        STYLE_MAP.put("gray", Style.EMPTY.withColor(ChatFormatting.GRAY));
        STYLE_MAP.put("dark_gray", Style.EMPTY.withColor(ChatFormatting.DARK_GRAY));
        STYLE_MAP.put("blue", Style.EMPTY.withColor(ChatFormatting.BLUE));
        STYLE_MAP.put("green", Style.EMPTY.withColor(ChatFormatting.GREEN));
        STYLE_MAP.put("aqua", Style.EMPTY.withColor(ChatFormatting.AQUA));
        STYLE_MAP.put("red", Style.EMPTY.withColor(ChatFormatting.RED));
        STYLE_MAP.put("light_purple", Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE));
        STYLE_MAP.put("yellow", Style.EMPTY.withColor(ChatFormatting.YELLOW));
        STYLE_MAP.put("white", Style.EMPTY.withColor(ChatFormatting.WHITE));
        // 格式
        STYLE_MAP.put("bold", Style.EMPTY.withBold(true));
        STYLE_MAP.put("italic", Style.EMPTY.withItalic(true));
        STYLE_MAP.put("strikethrough", Style.EMPTY.withStrikethrough(true));
        STYLE_MAP.put("underlined", Style.EMPTY.withUnderlined(true));
        STYLE_MAP.put("obfuscated", Style.EMPTY.withObfuscated(true));
        // 重置
        STYLE_MAP.put("reset", Style.EMPTY);
    }

    /**
     * 将包含 MiniMessage 标签的文本转换为 Minecraft {@link Component}。
     * 文本中的占位符（如 %player%、%context% 等）会被保留原样。
     */
    public static Component format(String text) {
        if (text == null || text.isEmpty()) {
            return Component.literal(text != null ? text : "");
        }

        // 第一步：将 MiniMessage 标签转换为 § 颜色代码
        String legacy = convertTagsToLegacy(text);

        // 第二步：将 § 颜色代码转换为 Minecraft Component
        return parseLegacyToComponent(legacy);
    }

    /**
     * 将 MiniMessage 标签转换为 § 颜色代码格式。
     */
    private static String convertTagsToLegacy(String text) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = TAG_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            // 添加标签之间的文本
            if (matcher.start() > lastEnd) {
                sb.append(text, lastEnd, matcher.start());
            }

            String tag = matcher.group(1);
            boolean isClosing = matcher.group().startsWith("</");

            if (isClosing) {
                // 关闭标签 → §r
                sb.append('§').append('r');
            } else {
                // 打开标签 → 查找对应的 § 代码
                String legacyCode = getLegacyCode(tag);
                if (legacyCode != null) {
                    sb.append(legacyCode);
                }
                // 如果标签不认识，忽略它（不添加任何内容）
            }

            lastEnd = matcher.end();
        }

        // 添加剩余文本
        if (lastEnd < text.length()) {
            sb.append(text.substring(lastEnd));
        }

        return sb.toString();
    }

    private static String getLegacyCode(String tag) {
        return switch (tag) {
            case "black" -> "§0";
            case "dark_blue" -> "§1";
            case "dark_green" -> "§2";
            case "dark_aqua" -> "§3";
            case "dark_red" -> "§4";
            case "dark_purple" -> "§5";
            case "gold" -> "§6";
            case "gray" -> "§7";
            case "dark_gray" -> "§8";
            case "blue" -> "§9";
            case "green" -> "§a";
            case "aqua" -> "§b";
            case "red" -> "§c";
            case "light_purple" -> "§d";
            case "yellow" -> "§e";
            case "white" -> "§f";
            case "bold" -> "§l";
            case "italic" -> "§o";
            case "strikethrough" -> "§m";
            case "underlined" -> "§n";
            case "obfuscated" -> "§k";
            case "reset" -> "§r";
            default -> null;
        };
    }

    /**
     * 将 § 颜色代码格式的字符串解析为 Minecraft Component。
     */
    private static Component parseLegacyToComponent(String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return Component.literal("");
        }

        MutableComponent result = Component.literal("");
        StringBuilder currentText = new StringBuilder();
        Style currentStyle = Style.EMPTY;

        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);

            if (c == '§' && i + 1 < legacy.length()) {
                // 有累积文本，先提交
                if (!currentText.isEmpty()) {
                    MutableComponent segment = Component.literal(currentText.toString()).withStyle(currentStyle);
                    result.append(segment);
                    currentText = new StringBuilder();
                }

                // 解析 § 后字符
                i++;
                char code = legacy.charAt(i);
                currentStyle = applyCode(code, currentStyle);
            } else {
                currentText.append(c);
            }
        }

        // 提交剩余文本
        if (!currentText.isEmpty()) {
            MutableComponent segment = Component.literal(currentText.toString()).withStyle(currentStyle);
            result.append(segment);
        }

        return result;
    }

    private static Style applyCode(char code, Style current) {
        ChatFormatting formatting = ChatFormatting.getByCode(code);
        if (formatting == null) return current;

        if (formatting.isColor()) {
            return current.withColor(formatting);
        } else if (formatting == ChatFormatting.RESET) {
            return Style.EMPTY;
        } else {
            // 格式修饰（bold, italic 等），叠加到当前样式
            return switch (formatting) {
                case BOLD -> current.withBold(true);
                case ITALIC -> current.withItalic(true);
                case STRIKETHROUGH -> current.withStrikethrough(true);
                case UNDERLINE -> current.withUnderlined(true);
                case OBFUSCATED -> current.withObfuscated(true);
                default -> current;
            };
        }
    }
}