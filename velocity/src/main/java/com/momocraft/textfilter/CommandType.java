package com.momocraft.textfilter;

import java.util.ArrayList;
import java.util.List;

public class CommandType {

    private final String name;
    private boolean enabled;
    private List<Integer> argsNumbers;
    private List<String> commands;
    private boolean extendToEnd;
    private List<String> prefixes;
    private boolean isPrefixMode;

    public CommandType(String name) {
        this.name = name;
        this.enabled = true;
        this.argsNumbers = new ArrayList<>();
        this.commands = new ArrayList<>();
        this.prefixes = new ArrayList<>();
        this.extendToEnd = false;
        this.isPrefixMode = false;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Integer> getArgsNumbers() {
        return argsNumbers;
    }

    public void setArgsNumbers(List<Integer> argsNumbers) {
        this.argsNumbers = argsNumbers;
    }

    public List<String> getCommands() {
        return commands;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands;
    }

    public boolean isExtendToEnd() {
        return extendToEnd;
    }

    public void setExtendToEnd(boolean extendToEnd) {
        this.extendToEnd = extendToEnd;
    }

    public List<String> getPrefixes() {
        return prefixes;
    }

    public void setPrefixes(List<String> prefixes) {
        this.prefixes = prefixes;
        this.isPrefixMode = !prefixes.isEmpty();
    }

    public boolean isPrefixMode() {
        return isPrefixMode;
    }

    public boolean matchesCommand(String message) {
        if (message == null) {
            return false;
        }
        
        // 前缀模式：匹配以特定前缀开头的消息（如 "!"）
        if (isPrefixMode) {
            for (String prefix : prefixes) {
                if (message.startsWith(prefix)) {
                    return true;
                }
            }
        }
        
        // 命令模式：匹配以 "/" 开头的命令
        if (!message.startsWith("/")) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String cmd : commands) {
            String lowerCmd = cmd.toLowerCase();
            if (lowerMessage.startsWith(lowerCmd)) {
                if (message.length() == cmd.length()) {
                    return true;
                }
                if (Character.isWhitespace(message.charAt(cmd.length()))) {
                    return true;
                }
            }
        }
        return false;
    }

    public String extractMessage(String message) {
        if (message == null) {
            return message;
        }

        // 前缀模式：提取前缀后的所有内容
        if (isPrefixMode) {
            for (String prefix : prefixes) {
                if (message.startsWith(prefix)) {
                    return message.substring(prefix.length()).trim();
                }
            }
            return message;
        }

        // 命令模式
        if (!message.startsWith("/")) {
            return message;
        }

        String lowerMessage = message.toLowerCase();
        for (String cmd : commands) {
            String lowerCmd = cmd.toLowerCase();
            if (lowerMessage.startsWith(lowerCmd)) {
                String remaining = message.substring(cmd.length()).trim();
                if (argsNumbers.isEmpty()) {
                    return remaining;
                }

                if (extendToEnd && !argsNumbers.isEmpty()) {
                    int minArg = Integer.MAX_VALUE;
                    for (Integer argNum : argsNumbers) {
                        if (argNum < minArg) {
                            minArg = argNum;
                        }
                    }

                    int currentArg = 0;
                    int startIndex = 0;
                    for (int i = 0; i < remaining.length(); i++) {
                        if (Character.isWhitespace(remaining.charAt(i))) {
                            currentArg++;
                            if (currentArg == minArg) {
                                startIndex = i + 1;
                                break;
                            }
                        }
                    }

                    if (currentArg < minArg) {
                        return remaining;
                    }
                    return remaining.substring(startIndex);
                }

                StringBuilder extracted = new StringBuilder();
                String[] parts = remaining.split("\\s+");

                for (Integer argNum : argsNumbers) {
                    int index = argNum - 1;
                    if (index < parts.length) {
                        if (extracted.length() > 0) {
                            extracted.append(" ");
                        }
                        extracted.append(parts[index]);
                    }
                }

                return extracted.toString();
            }
        }

        return message.substring(1).trim();
    }

    public String replaceMessage(String originalCommand, String filteredText) {
        if (originalCommand == null) {
            return originalCommand;
        }

        // 前缀模式：直接替换前缀后的内容
        if (isPrefixMode) {
            for (String prefix : prefixes) {
                if (originalCommand.startsWith(prefix)) {
                    return prefix + filteredText;
                }
            }
            return originalCommand;
        }

        // 命令模式
        if (!originalCommand.startsWith("/")) {
            return originalCommand;
        }

        String lowerMessage = originalCommand.toLowerCase();
        for (String cmd : commands) {
            String lowerCmd = cmd.toLowerCase();
            if (lowerMessage.startsWith(lowerCmd)) {
                String prefixPart = originalCommand.substring(0, cmd.length());
                String remaining = originalCommand.substring(cmd.length()).trim();

                if (argsNumbers.isEmpty()) {
                    return prefixPart + " " + filteredText;
                }

                if (extendToEnd && !argsNumbers.isEmpty()) {
                    int minArg = Integer.MAX_VALUE;
                    for (Integer argNum : argsNumbers) {
                        if (argNum < minArg) {
                            minArg = argNum;
                        }
                    }

                    int currentArg = 0;
                    int startIndex = 0;
                    for (int i = 0; i < remaining.length(); i++) {
                        if (Character.isWhitespace(remaining.charAt(i))) {
                            currentArg++;
                            if (currentArg == minArg) {
                                startIndex = i + 1;
                                break;
                            }
                        }
                    }

                    if (currentArg < minArg) {
                        return prefixPart + " " + filteredText;
                    }
                    return prefixPart + " " + remaining.substring(0, startIndex - 1) + " " + filteredText;
                }

                String[] parts = remaining.split("\\s+");
                StringBuilder result = new StringBuilder(prefixPart);

                int argIndex = 0;
                String[] filteredParts = filteredText.split("\\s+");

                for (int i = 0; i < parts.length; i++) {
                    result.append(" ");
                    if (argsNumbers.contains(i + 1) && argIndex < filteredParts.length) {
                        result.append(filteredParts[argIndex]);
                        argIndex++;
                    } else {
                        result.append(parts[i]);
                    }
                }

                return result.toString();
            }
        }

        return originalCommand;
    }
}
