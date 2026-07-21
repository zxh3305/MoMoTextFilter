package com.momocraft.textfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

public class TextFilterCommand implements CommandExecutor {

    private final TextFilter plugin;
    private final MiniMessage miniMessage;

    public TextFilterCommand(TextFilter plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                return handleReload(sender);
            case "info":
                return handleInfo(sender);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("textfilter.command")) {
            sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("command.no-permission")));
            return true;
        }

        plugin.getConfigManager().loadConfig();
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("warnings.reload-success")));
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("textfilter.command")) {
            sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("command.no-permission")));
            return true;
        }

        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("info.title")));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("info.version")));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("info.author")));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("info.desc")));
        
        Map<String, String> countPlaceholders = new HashMap<>();
        countPlaceholders.put("count", String.valueOf(plugin.getConfigManager().getBannedWords().size()));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("info.banned-words-count", countPlaceholders)));
        
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("info.footer")));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("command.help-title")));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("command.help-reload")));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("command.help-info")));
        sender.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getMessage("command.help-footer")));
    }
}
