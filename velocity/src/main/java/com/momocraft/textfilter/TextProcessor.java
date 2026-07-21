package com.momocraft.textfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Pattern AND_X_COLOR_CODE = Pattern.compile("&x&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])");
    private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[a-zA-Z0-9_]+(:[^>]*)?>");
    private static final Pattern LEGACY_COLOR_CODES = Pattern.compile("[§&][0-9a-fA-FklmnorxX]");
    private static final Pattern MINIMESSAGE_COLOR_TAGS = Pattern.compile("<#[0-9a-fA-F]{3,6}>");
    private static final Pattern AMP_HASH_COLOR = Pattern.compile("&#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})");
    
    private final String originalText;
    private final String processedText;
    private final List<Segment> segments;
    private final int[] originalToProcessed;
    private final int[] processedToOriginal;
    
    public TextProcessor(String text) {
        this.originalText = text;
        this.segments = splitByMiniMessageTags(text);
        this.processedText = extractVisibleText(segments);
        
        List<Integer> origToProc = new ArrayList<>();
        List<Integer> procToOrig = new ArrayList<>();
        buildPositionMapping(segments, origToProc, procToOrig);
        
        this.originalToProcessed = origToProc.stream().mapToInt(i -> i).toArray();
        this.processedToOriginal = procToOrig.stream().mapToInt(i -> i).toArray();
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
        return originalText;
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
            return originalText;
        }
        
        boolean[] toReplace = new boolean[originalText.length()];
        
        for (int[] range : processedRanges) {
            int start = range[0];
            int end = range[1];
            
            for (int i = start; i < end; i++) {
                int origIdx = getOriginalIndex(i);
                if (origIdx >= 0 && origIdx < toReplace.length) {
                    toReplace[origIdx] = true;
                }
            }
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < originalText.length(); i++) {
            if (toReplace[i]) {
                sb.append(replacement);
            } else {
                sb.append(originalText.charAt(i));
            }
        }
        
        return sb.toString();
    }
    
    public String replaceInOriginalWithMask(boolean[] mask, String replacement) {
        if (mask == null || mask.length == 0) {
            return originalText;
        }
        
        boolean[] toReplace = new boolean[originalText.length()];
        boolean tagHasBannedWord = false;
        
        for (int i = 0; i < mask.length && i < processedToOriginal.length; i++) {
            if (mask[i]) {
                int origIdx = getOriginalIndex(i);
                if (origIdx == -2) {
                    tagHasBannedWord = true;
                } else if (origIdx >= 0 && origIdx < toReplace.length) {
                    toReplace[origIdx] = true;
                }
            }
        }
        
        if (tagHasBannedWord) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < originalText.length(); i++) {
                if (toReplace[i]) {
                    sb.append(replacement);
                } else {
                    sb.append(originalText.charAt(i));
                }
            }
            String result = sb.toString();

            int tagContentStart = 0;
            for (Segment segment : segments) {
                if (segment.isTag && segment.extractedText != null && !segment.extractedText.isEmpty()) {
                    boolean shouldStrip = false;
                    for (int i = 0; i < segment.extractedText.length(); i++) {
                        int procIdx = tagContentStart + i;
                        if (procIdx < mask.length && mask[procIdx]) {
                            shouldStrip = true;
                            break;
                        }
                    }
                    if (shouldStrip) {
                        result = result.replace(segment.content, "");
                    }
                    tagContentStart += segment.extractedText.length();
                }
            }
            return result;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < originalText.length(); i++) {
            if (toReplace[i]) {
                sb.append(replacement);
            } else {
                sb.append(originalText.charAt(i));
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
    
    public static String stripAllFormatting(String text) {
        if (text == null) return null;
        text = AND_X_COLOR_CODE.matcher(text).replaceAll("");
        text = AMP_HASH_COLOR.matcher(text).replaceAll("");
        text = MINIMESSAGE_TAG.matcher(text).replaceAll("");
        text = LEGACY_COLOR_CODES.matcher(text).replaceAll("");
        return text;
    }
}