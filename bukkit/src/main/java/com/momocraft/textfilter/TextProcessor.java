package com.momocraft.textfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Pattern AND_X_COLOR_CODE = Pattern.compile("&x&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])");
    private static final Pattern LEGACY_COLOR_CODES = Pattern.compile("[§&][0-9a-fA-FklmnorxX]");
    private static final Pattern MINIMESSAGE_COLOR_TAGS = Pattern.compile("<#[0-9a-fA-F]{3,6}>");
    private static final Pattern AMP_HASH_COLOR = Pattern.compile("&#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})");

    // MiniMessage 有效标签名（不区分大小写）
    private static final String VALID_TAG_NAMES =
            "(?:yellow|white|black|red|green|blue|gray|grey|dark_gray|dark_grey|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|aqua|light_purple"
            + "|color|colour|c|shadow"
            + "|bold|b|italic|em|i|underlined|u|strikethrough|st|obfuscated|obf"
            + "|reset|r"
            + "|click|hover|key|lang|tr|translate|lang_or|tr_or|translate_or"
            + "|insert|rainbow|gradient|transition|font|newline|br"
            + "|selector|sel|score|nbt|pride|sprite|head)";

    // 仅匹配有效的 MiniMessage 标签：标签名 + 可选参数（参数可含引号字符串，引号内允许 '>'），
    // 支持 <tag>、</tag>、<!tag>（反转形式，如 <!shadow>、<!bold>）以及 #RRGGBB 十六进制颜色。
    // 无效/未知的 <...> 序列按普通文本处理，避免误删普通文本中的 < > 字符。
    private static final Pattern MINIMESSAGE_TAG = Pattern.compile(
            "</?!?" + VALID_TAG_NAMES + "(?::(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^>\"'])*)?/?>"
            + "|</?#[0-9a-fA-F]{3,6}>");

    private static final int MAX_EXTRACT_DEPTH = 5;

    private final String originalText;
    private final String rawText;
    private final String processedText;
    private final List<Segment> segments;
    private final int[] originalToProcessed;
    private final int[] processedToOriginal;
    /** 预处理后文本中每个字符对应的原始输入位置，-1 表示转换时新增的字符（如 < 和 >） */
    private final int[] preprocessedToRaw;

    public TextProcessor(String text) {
        this.rawText = text;
        // 先将 &x&R&R&G&G&B&B 和 &#RRGGBB 转换为 <#RRGGBB> 格式（记录到原始文本的位置映射）
        PreprocessedResult preprocessed = preprocess(text);
        this.originalText = preprocessed.text;
        this.preprocessedToRaw = preprocessed.map;
        this.segments = splitByMiniMessageTags(preprocessed.text);
        this.processedText = extractVisibleText(segments);

        List<Integer> origToProc = new ArrayList<>();
        List<Integer> procToOrig = new ArrayList<>();
        buildPositionMapping(segments, origToProc, procToOrig);

        this.originalToProcessed = origToProc.stream().mapToInt(i -> i).toArray();
        this.processedToOriginal = procToOrig.stream().mapToInt(i -> i).toArray();
    }

    /**
     * 预处理：将 &x&R&R&G&G&B&B 和 &#RRGGBB（含 3 位简写）转换为 <#RRGGBB> 格式。
     * 与 {@link ColorCodeUtils#convertAndXColorCodes} 及 convertAmpHashColor 逻辑保持一致，
     * 同时记录新文本每个字符对应的原始输入位置，用于替换时保留原始格式。
     */
    private static PreprocessedResult preprocess(String input) {
        if (input == null) {
            return new PreprocessedResult(null, new int[0]);
        }
        // 第一步：&x&H&H&H&H&H&H -> <#HHHHHH>
        StringBuilder sb = new StringBuilder(input.length());
        List<Integer> map = new ArrayList<>(input.length() + 8);
        int i = 0;
        while (i < input.length()) {
            if (i + 13 < input.length() && input.charAt(i) == '&' && input.charAt(i + 1) == 'x') {
                boolean valid = true;
                for (int j = 2; j <= 12; j += 2) {
                    if (input.charAt(i + j) != '&' || !isHexChar(input.charAt(i + j + 1))) {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    sb.append('<'); map.add(-1);
                    sb.append('#'); map.add(-1);
                    for (int j = 2; j <= 12; j += 2) {
                        sb.append(input.charAt(i + j + 1));
                        map.add(i + j + 1);
                    }
                    sb.append('>'); map.add(-1);
                    i += 14;
                    continue;
                }
            }
            sb.append(input.charAt(i));
            map.add(i);
            i++;
        }
        // 第二步：&#HHHHHH / &#HHH -> <#HHHHHH> / <#HHH>（组合映射）
        String first = sb.toString();
        StringBuilder sb2 = new StringBuilder(first.length());
        List<Integer> map2 = new ArrayList<>(first.length() + 8);
        i = 0;
        while (i < first.length()) {
            if (i + 1 < first.length() && first.charAt(i) == '&' && first.charAt(i + 1) == '#') {
                int hexLen = 0;
                if (i + 8 <= first.length()) {
                    boolean valid6 = true;
                    for (int j = 2; j < 8; j++) {
                        if (!isHexChar(first.charAt(i + j))) {
                            valid6 = false;
                            break;
                        }
                    }
                    if (valid6) hexLen = 6;
                }
                if (hexLen == 0 && i + 5 <= first.length()) {
                    boolean valid3 = true;
                    for (int j = 2; j < 5; j++) {
                        if (!isHexChar(first.charAt(i + j))) {
                            valid3 = false;
                            break;
                        }
                    }
                    if (valid3) hexLen = 3;
                }
                if (hexLen > 0) {
                    sb2.append('<'); map2.add(-1);
                    sb2.append('#'); map2.add(-1);
                    for (int j = 0; j < hexLen; j++) {
                        sb2.append(first.charAt(i + 2 + j));
                        map2.add(map.get(i + 2 + j));
                    }
                    sb2.append('>'); map2.add(-1);
                    i += 2 + hexLen;
                    continue;
                }
            }
            sb2.append(first.charAt(i));
            map2.add(map.get(i));
            i++;
        }
        int[] mapArr = new int[map2.size()];
        for (int k = 0; k < mapArr.length; k++) {
            mapArr[k] = map2.get(k);
        }
        return new PreprocessedResult(sb2.toString(), mapArr);
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static class PreprocessedResult {
        final String text;
        final int[] map;
        PreprocessedResult(String text, int[] map) {
            this.text = text;
            this.map = map;
        }
    }

    private List<Segment> splitByMiniMessageTags(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }

        Matcher matcher = MINIMESSAGE_TAG.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                segments.add(new Segment(text.substring(lastEnd, matcher.start()), false));
            }
            String tagContent = matcher.group();
            Extraction ext = extractFromTag(tagContent, 0);
            segments.add(new Segment(tagContent, true, ext.text, ext.offsets));
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            segments.add(new Segment(text.substring(lastEnd), false));
        }

        return segments;
    }

    /**
     * 提取 MiniMessage 标签中的有效展示内容，并记录每个展示字符在标签原文中的偏移（用于打码定位）。
     * <p>
     * 纯格式化标签（color/gradient/rainbow/shadow/font/decoration/reset/newline 等）本身不展示任何内容，
     * 与颜色代码一样直接剔除，返回空；携带文本载荷的标签（hover:show_text、click、insert）提取其载荷值
     * 作为有效展示内容参与违禁词检测。
     */
    private static Extraction extractFromTag(String tag, int depth) {
        if (depth > MAX_EXTRACT_DEPTH) {
            return EMPTY_EXTRACTION;
        }
        if (tag == null || tag.isEmpty() || tag.startsWith("</") || tag.startsWith("<!")) {
            return EMPTY_EXTRACTION; // 闭合标签 </tag> 与反转标签 <!tag> 均为纯格式，无展示内容
        }

        int contentStart = 1;
        int contentEnd = tag.length() - 1; // 去掉末尾 '>'
        if (contentEnd > contentStart && tag.charAt(contentEnd - 1) == '/') {
            contentEnd--; // 自闭合标签 <tag/>
        }

        String inner = tag.substring(contentStart, contentEnd);
        int colon = firstUnquotedColon(inner);
        String tagName = colon < 0 ? inner.toLowerCase() : inner.substring(0, colon).toLowerCase();
        if (tagName.isEmpty() || tagName.charAt(0) == '#') {
            return EMPTY_EXTRACTION; // <#RRGGBB> 十六进制颜色
        }

        switch (tagName) {
            case "hover": {
                String args = inner.substring(colon + 1);
                int actionColon = firstUnquotedColon(args);
                if (actionColon < 0) {
                    return EMPTY_EXTRACTION;
                }
                String action = args.substring(0, actionColon).toLowerCase();
                if (!action.equals("show_text")) {
                    return EMPTY_EXTRACTION; // show_item / show_entity 为物品/实体信息，非展示文本
                }
                String value = args.substring(actionColon + 1);
                int valueOffset = contentStart + colon + 1 + actionColon + 1;
                return extractPayload(value, valueOffset, depth);
            }
            case "click":
            case "insert": {
                String args = inner.substring(colon + 1);
                int valueOffset = contentStart + colon + 1;
                return extractPayload(args, valueOffset, depth);
            }
            case "lang":
            case "tr":
            case "translate":
            case "lang_or":
            case "tr_or":
            case "translate_or": {
                // 语法: <lang:key:'fallback1':'fallback2'>，第一个参数为翻译键，
                // 后续引号包裹的参数为语言回退文本（fallback），即实际会展示的内容，需提取参与检测。
                String args = inner.substring(colon + 1);
                int argsOffset = contentStart + colon + 1;
                return extractLangFallbacks(args, argsOffset, depth);
            }
            default:
                return EMPTY_EXTRACTION;
        }
    }

    /**
     * 提取 lang 系列标签中除翻译键外的所有引号参数（fallback 文本），
     * 逐个递归解析并拼接展示文本与偏移。例：
     * <pre>&lt;lang:commands.drop.success.single:'&lt;red&gt;fuck':'&lt;blue&gt;dick'&gt;</pre>
     * 提取出 "fuck" 与 "dick"。
     */
    private static Extraction extractLangFallbacks(String args, int baseOffset, int depth) {
        if (depth > MAX_EXTRACT_DEPTH || args == null || args.isEmpty()) {
            return EMPTY_EXTRACTION;
        }
        // 跳过第一个参数（翻译键）
        int firstColon = firstUnquotedColon(args);
        String rest = firstColon < 0 ? "" : args.substring(firstColon + 1);
        int restOffset = firstColon < 0 ? baseOffset + args.length() : baseOffset + firstColon + 1;

        StringBuilder sb = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        while (!rest.isEmpty()) {
            int colon = firstUnquotedColon(rest);
            String param = colon < 0 ? rest : rest.substring(0, colon);
            // 仅提取引号包裹的参数值（fallback 文本）
            if (param.length() >= 2 && ((param.charAt(0) == '"' && param.charAt(param.length() - 1) == '"')
                    || (param.charAt(0) == '\'' && param.charAt(param.length() - 1) == '\''))) {
                Extraction sub = extractPayload(param, restOffset, depth);
                for (int i = 0; i < sub.text.length(); i++) {
                    sb.append(sub.text.charAt(i));
                    offsets.add(sub.offsets[i]);
                }
            }
            if (colon < 0) {
                break;
            }
            rest = rest.substring(colon + 1);
            restOffset += colon + 1;
        }
        int[] arr = new int[offsets.size()];
        for (int k = 0; k < arr.length; k++) {
            arr[k] = offsets.get(k);
        }
        return new Extraction(sb.toString(), arr);
    }

    /** 去掉载荷值的外层引号（"..." 或 '...'）后递归解析 */
    private static Extraction extractPayload(String value, int valueOffset, int depth) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return extractDisplayText(value.substring(1, value.length() - 1), valueOffset + 1, depth);
            }
        }
        return extractDisplayText(value, valueOffset, depth);
    }

    /** 递归解析可能内嵌 MiniMessage 标签的载荷文本，收集纯文本及其在标签原文中的偏移 */
    private static Extraction extractDisplayText(String text, int baseOffset, int depth) {
        if (text == null || text.isEmpty()) {
            return EMPTY_EXTRACTION;
        }
        StringBuilder sb = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        Matcher m = MINIMESSAGE_TAG.matcher(text);
        int lastEnd = 0;
        while (m.find()) {
            for (int i = lastEnd; i < m.start(); i++) {
                sb.append(text.charAt(i));
                offsets.add(baseOffset + i);
            }
            Extraction sub = extractFromTag(m.group(), depth + 1);
            for (int k = 0; k < sub.text.length(); k++) {
                sb.append(sub.text.charAt(k));
                offsets.add(baseOffset + m.start() + sub.offsets[k]);
            }
            lastEnd = m.end();
        }
        for (int i = lastEnd; i < text.length(); i++) {
            sb.append(text.charAt(i));
            offsets.add(baseOffset + i);
        }
        int[] arr = new int[offsets.size()];
        for (int k = 0; k < arr.length; k++) {
            arr[k] = offsets.get(k);
        }
        return new Extraction(sb.toString(), arr);
    }

    /** 查找字符串中第一个不在引号内的冒号 */
    private static int firstUnquotedColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static class Extraction {
        final String text;
        final int[] offsets; // 提取出的每个展示字符在标签原始字符串中的偏移

        Extraction(String text, int[] offsets) {
            this.text = text;
            this.offsets = offsets;
        }
    }

    private static final Extraction EMPTY_EXTRACTION = new Extraction("", new int[0]);

    private String extractVisibleText(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.isTag) {
                if (segment.extractedText != null) {
                    sb.append(segment.extractedText);
                }
            } else {
                String text = segment.content;
                text = AND_X_COLOR_CODE.matcher(text).replaceAll("");
                text = AMP_HASH_COLOR.matcher(text).replaceAll("");
                text = LEGACY_COLOR_CODES.matcher(text).replaceAll("");
                text = MINIMESSAGE_COLOR_TAGS.matcher(text).replaceAll("");
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** 计算普通文本段去除颜色代码后的可见长度（与 extractVisibleText 的剥离逻辑一致） */
    private static int visibleTextLength(String content) {
        if (content == null) {
            return 0;
        }
        String text = content;
        text = AND_X_COLOR_CODE.matcher(text).replaceAll("");
        text = AMP_HASH_COLOR.matcher(text).replaceAll("");
        text = LEGACY_COLOR_CODES.matcher(text).replaceAll("");
        text = MINIMESSAGE_COLOR_TAGS.matcher(text).replaceAll("");
        return text.length();
    }

    private void buildPositionMapping(List<Segment> segments, List<Integer> origToProc, List<Integer> procToOrig) {
        int origPos = 0;
        int procPos = 0;

        for (Segment segment : segments) {
            if (segment.isTag) {
                // 标签的提取内容字符直接映射到标签原文（预处理文本）中的字符位置，
                // 这样检测到违禁词时可以在标签参数内部打码（如 <insert:Y**MY>）而非剥离整个标签。
                int tagStart = origPos;
                for (int i = 0; i < segment.content.length(); i++) {
                    origToProc.add(-1);
                    origPos++;
                }
                if (segment.extractedText != null && !segment.extractedText.isEmpty()) {
                    for (int i = 0; i < segment.extractedText.length(); i++) {
                        procToOrig.add(tagStart + segment.extractedOffsets[i]);
                        procPos++;
                    }
                }
            } else {
                int i = 0;
                while (i < segment.content.length()) {
                    char c = segment.content.charAt(i);

                    if (c == '&' && i + 1 < segment.content.length() && segment.content.charAt(i + 1) == 'x') {
                        if (i + 14 <= segment.content.length()) {
                            boolean isValid = true;
                            for (int j = 2; j <= 12; j += 2) {
                                char ampChar = segment.content.charAt(i + j);
                                char hexChar = segment.content.charAt(i + j + 1);
                                if (ampChar != '&' || !((hexChar >= '0' && hexChar <= '9') ||
                                       (hexChar >= 'a' && hexChar <= 'f') ||
                                       (hexChar >= 'A' && hexChar <= 'F'))) {
                                    isValid = false;
                                    break;
                                }
                            }
                            if (isValid) {
                                for (int j = 0; j < 14; j++) {
                                    origToProc.add(-1);
                                }
                                origPos += 14;
                                i += 14;
                                continue;
                            }
                        }
                    }

                    if (c == '&' && i + 1 < segment.content.length() && segment.content.charAt(i + 1) == '#') {
                        int hexLen = 0;
                        if (i + 8 <= segment.content.length()) {
                            boolean valid6 = true;
                            for (int j = 2; j < 8; j++) {
                                char h = segment.content.charAt(i + j);
                                if (!((h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F'))) {
                                    valid6 = false;
                                    break;
                                }
                            }
                            if (valid6) hexLen = 8;
                        }
                        if (hexLen == 0 && i + 5 <= segment.content.length()) {
                            boolean valid3 = true;
                            for (int j = 2; j < 5; j++) {
                                char h = segment.content.charAt(i + j);
                                if (!((h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F'))) {
                                    valid3 = false;
                                    break;
                                }
                            }
                            if (valid3) hexLen = 5;
                        }
                        if (hexLen > 0) {
                            for (int j = 0; j < hexLen; j++) {
                                origToProc.add(-1);
                            }
                            origPos += hexLen;
                            i += hexLen;
                            continue;
                        }
                    }

                    if ((c == '&' || c == '§') && i + 1 < segment.content.length()) {
                        char next = segment.content.charAt(i + 1);
                        if ((next >= '0' && next <= '9') || (next >= 'a' && next <= 'f') ||
                            (next >= 'A' && next <= 'F') || "klmnorX".indexOf(next) >= 0) {
                            origToProc.add(-1);
                            origToProc.add(-1);
                            origPos += 2;
                            i += 2;
                            continue;
                        }
                    }

                    if (c == '<' && i + 1 < segment.content.length() && segment.content.charAt(i + 1) == '#') {
                        int end = segment.content.indexOf('>', i);
                        if (end > i) {
                            int len = end - i + 1;
                            for (int j = 0; j < len; j++) {
                                origToProc.add(-1);
                            }
                            origPos += len;
                            i = end + 1;
                            continue;
                        }
                    }

                    origToProc.add(procPos);
                    procToOrig.add(origPos);
                    origPos++;
                    procPos++;
                    i++;
                }
            }
        }
    }

    public String getOriginalText() {
        return rawText;
    }

    public String getRawText() {
        return rawText;
    }

    public String getProcessedText() {
        return processedText;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public int getProcessedIndex(int originalIndex) {
        if (originalIndex < 0 || originalIndex >= originalToProcessed.length) {
            return -1;
        }
        return originalToProcessed[originalIndex];
    }

    public int getOriginalIndex(int processedIndex) {
        if (processedIndex < 0 || processedIndex >= processedToOriginal.length) {
            return -1;
        }
        return processedToOriginal[processedIndex];
    }

    public String replaceInOriginal(List<int[]> processedRanges, String replacement) {
        if (processedRanges == null || processedRanges.isEmpty()) {
            return rawText;
        }

        boolean[] toReplace = new boolean[rawText.length()];

        for (int[] range : processedRanges) {
            int start = range[0];
            int end = range[1];

            for (int i = start; i < end; i++) {
                int origIdx = getOriginalIndex(i);
                if (origIdx >= 0 && origIdx < preprocessedToRaw.length) {
                    int rawIdx = preprocessedToRaw[origIdx];
                    if (rawIdx >= 0 && rawIdx < toReplace.length) {
                        toReplace[rawIdx] = true;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawText.length(); i++) {
            if (toReplace[i]) {
                sb.append(replacement);
            } else {
                sb.append(rawText.charAt(i));
            }
        }

        return sb.toString();
    }

    /**
     * 根据可见文本位置的掩码，在原始输入中对应位置打码。
     * 标签提取内容的掩码会直接映射回标签参数内部（如 &lt;insert:Y**MY&gt;），
     * 保留标签结构，使 shift 点击等交互功能不受影响。
     */
    public String replaceInOriginalWithMask(boolean[] mask, String replacement) {
        if (mask == null || mask.length == 0) {
            return rawText;
        }

        boolean[] toReplace = new boolean[rawText.length()];

        for (int i = 0; i < mask.length && i < processedToOriginal.length; i++) {
            if (mask[i]) {
                int origIdx = getOriginalIndex(i);
                if (origIdx >= 0 && origIdx < preprocessedToRaw.length) {
                    int rawIdx = preprocessedToRaw[origIdx];
                    if (rawIdx >= 0 && rawIdx < toReplace.length) {
                        toReplace[rawIdx] = true;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawText.length(); i++) {
            if (toReplace[i]) {
                sb.append(replacement);
            } else {
                sb.append(rawText.charAt(i));
            }
        }

        return sb.toString();
    }

    public static class Segment {
        final String content;
        final boolean isTag;
        final String extractedText;
        final int[] extractedOffsets;

        Segment(String content, boolean isTag) {
            this.content = content;
            this.isTag = isTag;
            this.extractedText = null;
            this.extractedOffsets = new int[0];
        }

        Segment(String content, boolean isTag, String extractedText) {
            this.content = content;
            this.isTag = isTag;
            this.extractedText = extractedText;
            this.extractedOffsets = new int[0];
        }

        Segment(String content, boolean isTag, String extractedText, int[] extractedOffsets) {
            this.content = content;
            this.isTag = isTag;
            this.extractedText = extractedText;
            this.extractedOffsets = extractedOffsets != null ? extractedOffsets : new int[0];
        }
    }

    private static String convertAmpHashColor(String text) {
        if (text == null) return null;
        Matcher matcher = AMP_HASH_COLOR.matcher(text);
        return matcher.replaceAll(mr -> "<#" + mr.group(1) + ">");
    }

    public static String stripAllFormatting(String text) {
        if (text == null) return null;
        text = AND_X_COLOR_CODE.matcher(text).replaceAll("");
        text = AMP_HASH_COLOR.matcher(text).replaceAll("");
        text = MINIMESSAGE_TAG.matcher(text).replaceAll("");
        text = LEGACY_COLOR_CODES.matcher(text).replaceAll("");
        return text;
    }
}
