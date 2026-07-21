package com.momocraft.textfilter;

import java.util.ArrayList;
import java.util.List;

public class BannedWordDetection {

    private String filteredText;
    private List<BannedWordInfo> detectedWords;

    public BannedWordDetection(String filteredText) {
        this.filteredText = filteredText;
        this.detectedWords = new ArrayList<>();
    }

    public String getFilteredText() {
        return filteredText;
    }

    public void setFilteredText(String filteredText) {
        this.filteredText = filteredText;
    }

    public List<BannedWordInfo> getDetectedWords() {
        return detectedWords;
    }

    public void addDetectedWord(String word, String level) {
        for (BannedWordInfo info : detectedWords) {
            if (info.getWord().equals(word) && info.getLevel().equals(level)) {
                return;
            }
        }
        detectedWords.add(new BannedWordInfo(word, level));
    }

    public boolean hasDetectedWords() {
        return !detectedWords.isEmpty();
    }

    public String getFirstBannedWord() {
        return detectedWords.isEmpty() ? "" : detectedWords.get(0).getWord();
    }

    public String getFirstLevel() {
        return detectedWords.isEmpty() ? "" : detectedWords.get(0).getLevel();
    }

    public static class BannedWordInfo {
        private final String word;
        private final String level;

        public BannedWordInfo(String word, String level) {
            this.word = word;
            this.level = level;
        }

        public String getWord() {
            return word;
        }

        public String getLevel() {
            return level;
        }
    }
}
