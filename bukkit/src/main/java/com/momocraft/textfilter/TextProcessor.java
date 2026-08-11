package com.momocraft.textfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Pattern AND_X_COLOR_CODE = Pattern.compile("&x&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])");
    private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[^>]+>");
    private static final Pattern LEGACY_COLOR_CODES = Pattern.compile("[§&][0-9a-fA-FklmnorxX]");
    private static final Pattern MINIMESSAGE_COLOR_TAGS = Pattern.compile("<#[0-9a-fA-F]{3,6}>");
    private static final Pattern AMP_HASH_COLOR = Pattern.compile("&#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})");
    
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
            String extractedText = extractTextFromTag(tagContent);
            segments.add(new Segment(tagContent, true, extractedText));
            lastEnd = matcher.end();
        }
        
        if (lastEnd < text.length()) {
            segments.add(new Segment(text.substring(lastEnd), false));
        }
        
        return segments;
    }
    
    private String extractTextFromTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        if (tag.startsWith("</")) {
            return "";
        }
        // 颜色代码标签 <#RRGGBB> 不提取内容
        if (tag.startsWith("<#") && tag.endsWith(">")) {
            return "";
        }
        int colonIndex = tag.indexOf(':');
        if (colonIndex > 0 && colonIndex < tag.length() - 1) {
            int endIndex = tag.indexOf('>', colonIndex);
            if (endIndex > colonIndex) {
                return tag.substring(colonIndex + 1, endIndex);
            }
        }
        int start = 1;
        int end = tag.lastIndexOf('>');
        if (end > start) {
            return tag.substring(start, end);
        }
        return "";
    }
    
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
                for (int i = 0; i < segment.content.length(); i++) {
                    origToProc.add(-1);
                    origPos++;
                }
                if (segment.extractedText != null && !segment.extractedText.isEmpty()) {
                    for (int i = 0; i < segment.extractedText.length(); i++) {
                        procToOrig.add(-2);
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
        int result = processedToOriginal[processedIndex];
        if (result == -2) {
            return -2;
        }
        return result;
    }
    
    public boolean isInTag(int processedIndex) {
        if (processedIndex < 0 || processedIndex >= processedToOriginal.length) {
            return false;
        }
        return processedToOriginal[processedIndex] == -2;
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
    
    public String replaceInOriginalWithMask(boolean[] mask, String replacement) {
        if (mask == null || mask.length == 0) {
            return rawText;
        }
        
        boolean[] toReplace = new boolean[rawText.length()];
        boolean tagHasBannedWord = false;
        
        for (int i = 0; i < mask.length && i < processedToOriginal.length; i++) {
            if (mask[i]) {
                int origIdx = getOriginalIndex(i);
                if (origIdx == -2) {
                    tagHasBannedWord = true;
                } else if (origIdx >= 0 && origIdx < preprocessedToRaw.length) {
                    int rawIdx = preprocessedToRaw[origIdx];
                    if (rawIdx >= 0 && rawIdx < toReplace.length) {
                        toReplace[rawIdx] = true;
                    }
                }
            }
        }
        
        if (tagHasBannedWord) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rawText.length(); i++) {
                if (toReplace[i]) {
                    sb.append(replacement);
                } else {
                    sb.append(rawText.charAt(i));
                }
            }
            String result = sb.toString();

            // 按可见文本顺序跟踪各标签提取文本的起始位置，剥离含违禁词的标签
            int visiblePos = 0;
            for (Segment segment : segments) {
                if (segment.isTag) {
                    if (segment.extractedText != null && !segment.extractedText.isEmpty()) {
                        boolean shouldStrip = false;
                        for (int i = 0; i < segment.extractedText.length(); i++) {
                            int procIdx = visiblePos + i;
                            if (procIdx < mask.length && mask[procIdx]) {
                                shouldStrip = true;
                                break;
                            }
                        }
                        if (shouldStrip) {
                            result = result.replace(segment.content, "");
                        }
                        visiblePos += segment.extractedText.length();
                    }
                } else {
                    visiblePos += visibleTextLength(segment.content);
                }
            }
            return result;
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
        
        Segment(String content, boolean isTag) {
            this.content = content;
            this.isTag = isTag;
            this.extractedText = null;
        }
        
        Segment(String content, boolean isTag, String extractedText) {
            this.content = content;
            this.isTag = isTag;
            this.extractedText = extractedText;
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
